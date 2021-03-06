package com.github.mproberts.rxtools.map;

import io.reactivex.*;
import io.reactivex.functions.Function;
import org.reactivestreams.Processor;
import org.reactivestreams.Subscription;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import io.reactivex.disposables.Disposable;
import io.reactivex.functions.Action;
import io.reactivex.functions.Consumer;
import io.reactivex.processors.BehaviorProcessor;

/**
 * SubjectMap manages the connection between an entity store and subscribers who are
 * interested in the updates to those entities.
 * <p>
 * The SubjectMap depends on the garbage collector to clean up unreferenced observables
 * when the weak references are automatically cleared. Subjects will be retained strongly
 * so long as a subscriber is subscribed and are weakly retained outside of that lifecycle.
 *
 * @param <K> key type for the collection
 * @param <V> value type for the emissions from the observables of the collection
 */
public class SubjectMap<K, V>
{
    private static final Action EMPTY_ACTION = new Action() {
        @Override
        public void run()
        {
        }
    };

    private final Lock _writeLock;
    private final Lock _readLock;

    private final HashMap<K, WeakReference<Flowable<V>>> _weakCache;
    private final HashMap<K, WeakReference<Processor<V, V>>> _weakSources;

    private final HashMap<K, Flowable<V>> _cache;

    private final BehaviorProcessor<K> _faults;
    private final BehaviorProcessor<List<K>> _multiFaults;

    private Function<K, Single<V>> _faultHandler;
    private Function<List<K>, Single<List<V>>> _multiFaultHandler;

    private class OnSubscribeAttach implements FlowableOnSubscribe<V>
    {
        private final AtomicBoolean _isFirstFault = new AtomicBoolean(true);
        private final AtomicBoolean _valueSet = new AtomicBoolean(false);
        private final K _key;
        private volatile Processor<V, V> _valueObservable;
        private volatile WeakReference<Processor<V,V>> _weakObservable;
        private Function<K, Single<V>> _faultHandler;
        Completable _attachedFetch = null;

        OnSubscribeAttach(K key, Function<K, Single<V>> faultHandler)
        {
            _key = key;
            _faultHandler = faultHandler;
        }

        @Override
        public void subscribe(final FlowableEmitter<V> emitter) throws Exception
        {
            boolean isFirst = _isFirstFault.getAndSet(false);

            if (isFirst) {
                _valueObservable = attachSource(_key);

                // since this is the first fetch of the observable, go grab the first emission
                _attachedFetch = Completable.defer(new Callable<CompletableSource>() {
                    @Override
                    public CompletableSource call() throws Exception {
                        return new CompletableSource() {
                            @Override
                            public void subscribe(final CompletableObserver completableObserver) {
                                emitFault(_key);

                                if (_faultHandler != null) {
                                    try {
                                        Single<V> fault = _faultHandler.apply(_key);

                                        fault.doOnSuccess(new Consumer<V>() {
                                            @Override
                                            public void accept(V v) throws Exception {
                                                if (_valueSet.compareAndSet(false, true)) {
                                                    Processor<V, V> valueObservable = _weakObservable.get();

                                                    if (valueObservable != null) {
                                                        valueObservable.onNext(v);
                                                    }
                                                }
                                            }
                                        }).toCompletable().subscribe(new Action() {
                                            @Override
                                            public void run() throws Exception {
                                            }
                                        }, new Consumer<Throwable>() {
                                            @Override
                                            public void accept(Throwable throwable) throws Exception {
                                                Processor<V, V> valueObservable = _weakObservable.get();

                                                if (valueObservable != null) {
                                                    valueObservable.onError(throwable);
                                                }
                                            }
                                        });
                                    } catch (Exception e) {
                                        completableObserver.onError(e);
                                    }
                                } else {
                                    completableObserver.onComplete();
                                }
                            }
                        };
                    }
                })
                .cache();
            }

            // in case you raced into this block but someone else won the coin toss
            // and is still setting up the value observable
            while (_valueObservable == null) {
                // just chill out and let the other thread do the setup
                Thread.yield();
            }

            _weakObservable = new WeakReference<>(_valueObservable);

            final Completable initialValueFetch = _attachedFetch;
            final AtomicReference<Subscription> disposableTarget = new AtomicReference<>();
            final AtomicReference<Disposable> initialValueFetchDisposable = new AtomicReference<>();

            _valueObservable.subscribe(new FlowableSubscriber<V>() {

                @Override
                public void onSubscribe(Subscription s)
                {
                    if (initialValueFetch != null) {
                        Disposable subscription = initialValueFetch.subscribe(new Action() {
                            @Override
                            public void run() throws Exception {
                            }
                        }, new Consumer<Throwable>() {
                            @Override
                            public void accept(Throwable throwable) throws Exception {
                                Processor<V, V> processor = _weakObservable.get();

                                if (processor != null) {
                                    processor.onError(throwable);
                                }
                            }
                        });
                        initialValueFetchDisposable.set(subscription);
                    }

                    disposableTarget.set(s);

                    s.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(V v) {
                    _valueSet.set(true);
                    emitter.onNext(v);
                }

                @Override
                public void onError(Throwable e) {
                    _valueSet.set(true);
                    emitter.onError(e);
                }

                @Override
                public void onComplete() {
                    _valueSet.set(true);
                    emitter.onComplete();
                }
            });

            emitter.setDisposable(new Disposable() {
                public boolean _isDisposed;

                @Override
                public void dispose() {
                    Disposable disposable = initialValueFetchDisposable.get();

                    if (disposable != null && !disposable.isDisposed()) {
                        disposable.dispose();
                    }

                    disposableTarget.get().cancel();
                    detachSource(_key);
                }

                @Override
                public boolean isDisposed() {
                    return _isDisposed;
                }
            });
        }
    }

    /**
     * Constructs a new, empty SubjectMap
     */
    public SubjectMap()
    {
        ReadWriteLock _readWriteLock = new ReentrantReadWriteLock();

        _readLock = _readWriteLock.readLock();
        _writeLock = _readWriteLock.writeLock();

        _weakCache = new HashMap<>();
        _cache = new HashMap<>();
        _faults = BehaviorProcessor.create();
        _multiFaults = BehaviorProcessor.create();

        _weakSources = new HashMap<>();
    }

    private Processor<V, V> attachSource(K key)
    {
        _writeLock.lock();
        try {
            // if our source is being attached, we expect that all existing sources have been
            // cleaned up properly. If not, this is a serious issue
            assert(!_weakSources.containsKey(key));

            Processor<V, V> value = BehaviorProcessor.create();

            WeakReference<Flowable<V>> weakConnector = _weakCache.get(key);

            // if an observable is being attached then it must have been added to the weak cache
            // and it must still be referenced
            Flowable<V> connector = weakConnector.get();

            // the observable must be retained by someone since it is being attached
            assert(connector != null);

            // strongly retain the observable and add the subject so future next
            // calls will be piped through the subject
            _weakSources.put(key, new WeakReference<>(value));
            _cache.put(key, connector);

            return value;
        }
        finally {
            _writeLock.unlock();
        }
    }

    private void detachSource(K key)
    {
        _writeLock.lock();
        try {
            _cache.remove(key);
        }
        finally {
            _writeLock.unlock();
        }
    }

    private void emitUpdate(K key, Consumer<Processor<V, V>> updater, Action missHandler)
    {
        emitUpdate(key, updater, missHandler, false);
    }

    private void emitUpdate(K key, Consumer<Processor<V, V>> updater, Action missHandler, boolean disconnect)
    {
        Processor<V, V> subject = null;

        if (disconnect) {
            _writeLock.lock();
        }
        else {
            _readLock.lock();
        }

        try {
            // if we have a subject, we will emit the new value on the subject
            if (_weakSources.containsKey(key)) {
                WeakReference<Processor<V, V>> weakSource = _weakSources.get(key);

                subject = weakSource.get();
            }

            if (disconnect) {
                _weakSources.remove(key);
                _weakCache.remove(key);
                _cache.remove(key);
            }
        }
        finally {
            if (disconnect) {
                _writeLock.unlock();
            }
            else {
                _readLock.unlock();
            }

        }

        try {
            if (subject != null) {
                updater.accept(subject);
            }
            else {
                missHandler.run();
            }
        }
        catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void emitFault(K key)
    {
        _faults.onNext(key);
    }

    public void setFaultHandler(Function<K, Single<V>> faultHandler)
    {
        _faultHandler = faultHandler;
        _multiFaultHandler = null;
    }

    public void setMultiFaultHandler(Function<List<K>, Single<List<V>>> faultHandler)
    {
        _multiFaultHandler = faultHandler;
        _faultHandler = null;
    }

    /**
     * Returns a stream of keys indicating which values need to be faulted in to satisfy
     * the observables which have been requested through the system
     *
     * @return an observable stream of keys
     */
    public Flowable<K> faults()
    {
        return _faults;
    }

    /**
     * Returns a stream of keys indicating which values need to be faulted in to satisfy
     * the observables which have been requested through the system
     *
     * @return an observable stream of keys
     */
    public Flowable<List<K>> multiFaults()
    {
        return _multiFaults;
    }

    /**
     * Re-emits a fault for the specified key if there is someone bound
     */
    public Completable faultIfBound(final K key)
    {
        return Completable.defer(new Callable<CompletableSource>() {
            @Override
            public CompletableSource call() throws Exception {
                return new CompletableSource() {
                    @Override
                    public void subscribe(CompletableObserver completableObserver) {
                        _readLock.lock();
                        List<K> retainedKeys = new ArrayList<>(1);
                        try {
                            WeakReference<Processor<V, V>> weakSource = _weakSources.get(key);
                            if (weakSource != null && weakSource.get() != null) {
                                retainedKeys.add(key);
                            }
                        }
                        finally {
                            _readLock.unlock();
                        }
                        processFaultForRetainedKeys(retainedKeys, completableObserver);
                    }
                };
            }
        });
    }

    /**
     * Re-emits a fault for all bound keys
     */
    public Completable faultAllBound()
    {
        return Completable.defer(new Callable<CompletableSource>() {
            @Override
            public CompletableSource call() throws Exception {
                return new CompletableSource() {
                    @Override
                    public void subscribe(CompletableObserver completableObserver) {
                        List<K> retainedKeys = new ArrayList<>(_weakSources.size());
                        _readLock.lock();

                        try {
                            for (K key : _weakSources.keySet()) {
                                WeakReference<Processor<V, V>> weakSource = _weakSources.get(key);
                                if (weakSource != null && weakSource.get() != null) {
                                    retainedKeys.add(key);
                                }
                            }
                        }
                        finally {
                            _readLock.unlock();
                        }
                        processFaultForRetainedKeys(retainedKeys, completableObserver);
                    }
                };
            }
        });
    }

    /**
     * For all keys passed in, emit faults and fetch faulted value from a fault handler (if one is set)
     * and emit the new value for all processors that are still listening.
     *
     * @param retainedKeys keys that should be faulted if a faultHandler is set
     * @param completableObserver observer on which faults should be subscribed with
     */
    private void processFaultForRetainedKeys(final List<K> retainedKeys, CompletableObserver completableObserver) {
        // Only process the fault if there are any keys bound
        if (retainedKeys.isEmpty()) {
            Completable.complete().subscribe(completableObserver);
            return;
        }

        // Emit all faults for old fault handlers
        for (K key : retainedKeys) {
            _faults.onNext(key);
        }

        _multiFaults.onNext(retainedKeys);

        // Only process faults if there is a handler explicitly set
        if (_faultHandler != null) {
            Function<K, Single<V>> faultHandler = _faultHandler;
            try {
                List<Completable> faultCompletables = new ArrayList<>(retainedKeys.size());
                for (final K key : retainedKeys) {
                    Single<V> fault = faultHandler.apply(key);

                    faultCompletables.add(fault.doOnSuccess(new Consumer<V>() {
                        @Override
                        public void accept(V v) throws Exception {
                            _readLock.lock();
                            WeakReference<Processor<V, V>> weakSource = _weakSources.get(key);
                            _readLock.unlock();
                            Processor<V, V> processor;
                            if (weakSource != null && (processor = weakSource.get()) != null) {
                                processor.onNext(v);
                            }
                        }
                    }).toCompletable());
                }
                Completable.merge(faultCompletables).subscribe(completableObserver);
            }
            catch (Exception e) {
                Completable.error(e).subscribe(completableObserver);
            }
        } else if (_multiFaultHandler != null)  {
            Function<List<K>, Single<List<V>>> faultHandler = _multiFaultHandler;
            try {
                Single<List<V>> multiFault = faultHandler.apply(retainedKeys);

                multiFault
                        .doOnSuccess(new Consumer<List<V>>() {
                            @Override
                            public void accept(List<V> vs) throws Exception {
                                if (vs.size() != retainedKeys.size()) {
                                    throw new IllegalStateException("Multifault handler returned result of incorrect size.") ;
                                }

                                for (int i = 0; i < retainedKeys.size(); i++) {
                                    K key = retainedKeys.get(i);
                                    V value = vs.get(i);

                                    _readLock.lock();
                                    WeakReference<Processor<V, V>> weakSource = _weakSources.get(key);
                                    _readLock.unlock();

                                    Processor<V, V> processor;
                                    if (weakSource != null && (processor = weakSource.get()) != null) {
                                        processor.onNext(value);
                                    }
                                }
                            }
                        })
                        .toCompletable()
                        .subscribe(completableObserver);
            }
            catch (Exception e) {
                Completable.error(e).subscribe(completableObserver);
            }
        } else {
            Completable.complete().subscribe(completableObserver);
            return;
        }

    }

    /**
     * Emits the specified value from the observable associated with the specified key
     * if there is an associated observable. If no observable has subscribed to the key,
     * this operation is a noop. If no value is not emitted it will be faulted in later
     * should another query request it
     *
     * @param key key with which the specified value is to be associated
     * @param valueProvider the method to be called to create the new value in the case of a hit
     * @param missHandler the callback for when a subscriber has not been bound
     */
    public void onNext(K key, final Callable<V> valueProvider, Action missHandler)
    {
        emitUpdate(key, new Consumer<Processor<V, V>>() {
            @Override
            public void accept(Processor<V, V> subject)
            {
                try {
                    subject.onNext(valueProvider.call());
                }
                catch (Exception error) {
                    subject.onError(error);
                }
            }
        }, missHandler);
    }

    /**
     * Emits the specified value from the observable associated with the specified key
     * if there is an associated observable. If no observable has subscribed to the key,
     * this operation is a noop. If no value is not emitted it will be faulted in later
     * should another query request it
     *
     * @param key key with which the specified value is to be associated
     * @param valueCreator the method to be called to create the new value in the case of a hit
     */
    public void onNext(K key, final Callable<V> valueCreator)
    {
        onNext(key, valueCreator, EMPTY_ACTION);
    }

    /**
     * Emits the specified value from the observable associated with the specified key
     * if there is an associated observable. If no observable has subscribed to the key,
     * this operation is a noop. If no value is not emitted it will be faulted in later
     * should another query request it
     *
     * @param key key with which the specified value is to be associated
     * @param value value to be send to the specified observable
     */
    public void onNext(K key, final V value)
    {
        onNext(key, new Callable<V>() {
            @Override
            public V call() throws Exception
            {
                return value;
            }
        });
    }

    /**
     * Emits the error from the observable associated with the specified key. After the
     * error is emitted, the observable will be automatically unbound, subsequent calls
     * to get will return a new observable and attempt to fault the value in
     *
     * @param key key with which the specified value is to be associated
     * @param error exception to be sent to the specified observable
     */
    public void onError(K key, final Throwable error)
    {
        emitUpdate(key, new Consumer<Processor<V, V>>() {
            @Override
            public void accept(Processor<V, V> subject)
            {
                subject.onError(error);
            }
        }, EMPTY_ACTION, true);
    }

    /**
     * Returns a list of observables associated with their respective keys. The observable
     * will request that a value be supplied when the observable is bound and automatically
     * manage the lifecycle of the observable internally.
     *
     * All keys will emit faults together if a value does not yet exist. If a value already
     * exists for a particular key, that key will be skipped.
     *
     * @param keys list of keys whose associated observables will be returned
     *
     * @return a list of observables which, when subscribed, will be bound to the respective key
     * and will receive all emissions and errors for the specified key
     */
    public List<Flowable<V>> getAll(List<K> keys)
    {
        WeakReference<Flowable<V>> weakObservable;
        int remainingCount = keys.size();

        ArrayList<K> remainingKeys = new ArrayList<>(keys.size());
        ArrayList<Flowable<V>> values = new ArrayList<>(keys.size());

        for (int i = 0, l = keys.size(); i < l; ++i) {
            values.add(null);
        }

        remainingKeys.addAll(keys);

        _readLock.lock();

        try {
            // attempt to retrieve the weakly held observables
            for (int i = 0, l = keys.size(); i < l; ++i) {
                Flowable<V> observable;
                K key = remainingKeys.get(i);

                if (_weakCache.containsKey(key)) {
                    weakObservable = _weakCache.get(key);
                    observable = weakObservable.get();

                    if (observable != null) {
                        // we already have a cached observable bound to this key
                        values.set(i, observable);
                        remainingKeys.set(i, null);
                        --remainingCount;
                    }
                }
            }

            // found caches for all values
            if (remainingCount == 0) {
                return values;
            }

            // we do not have an observable for the key, escalate the lock
            _readLock.unlock();
            _writeLock.lock();

            try {
                // recheck the observable since we had to retake the lock
                for (int i = 0, l = keys.size(); i < l; ++i) {
                    Flowable<V> observable;
                    K key = remainingKeys.get(i);

                    if (_weakCache.containsKey(key)) {
                        weakObservable = _weakCache.get(key);
                        observable = weakObservable.get();

                        if (observable != null) {
                            // we found a hit this time around, return the hit
                            values.set(i, observable);
                            remainingKeys.set(i, null);

                            --remainingCount;
                        } else {
                            // the target of the weak source should have already been cleared by the
                            // garbage collector since the source is retained by the cached observable
                            _weakSources.remove(key);
                        }
                    }
                }

                // found caches for all values, after re-checks
                if (remainingCount == 0) {
                    return values;
                }

                final ArrayList<K> filteredKeys = new ArrayList<>(remainingCount);

                for (int i = 0, l = keys.size(); i < l; ++i) {
                    K key = remainingKeys.get(i);

                    if (key != null) {
                        filteredKeys.add(key);
                    }
                }

                Function<K, Single<V>> faultHandler;

                if (_multiFaultHandler != null) {
                    faultHandler = new Function<K, Single<V>>() {
                        private volatile Single<List<V>> _allFetchedValues;

                        void prepare() {
                            if (_allFetchedValues == null) {
                                try {
                                    _multiFaults.onNext(filteredKeys);

                                    _allFetchedValues = _multiFaultHandler.apply(filteredKeys).cache();
                                } catch (Exception e) {
                                    _allFetchedValues = Single.error(e);
                                }
                            }
                        }

                        @Override
                        public Single<V> apply(K k) throws Exception {
                            prepare();

                            _faults.onNext(k);

                            final int index = filteredKeys.indexOf(k);

                            return _allFetchedValues.map(new Function<List<V>, V>() {
                                @Override
                                public V apply(List<V> values) throws Exception {
                                    return values.get(index);
                                }
                            });
                        }
                    };
                } else {
                    faultHandler = _faultHandler;
                }

                for (int i = 0, l = keys.size(); i < l; ++i) {
                    K key = remainingKeys.get(i);

                    if (key == null) {
                        continue;
                    }

                    // no observable was found in the cache, create a new binding
                    Flowable<V> observable = Flowable.create(
                        new OnSubscribeAttach(key, faultHandler),
                        BackpressureStrategy.LATEST
                    );

                    values.set(i, observable);

                    _weakCache.put(key, new WeakReference<>(observable));
                }
            }
            finally {
                _readLock.lock();
                _writeLock.unlock();
            }

            return values;
        }
        finally {
            _readLock.unlock();
        }
    }

    /**
     * Returns an observable associated with the specified key. The observable will
     * request that a value be supplied when the observable is bound and automatically
     * manage the lifecycle of the observable internally
     *
     * @param key the key whose associated observable is to be returned
     *
     * @return an observable which, when subscribed, will be bound to the specified key
     * and will receive all emissions and errors for the specified key
     */
    public Flowable<V> get(K key)
    {
        WeakReference<Flowable<V>> weakObservable;

        _readLock.lock();

        try {
            Flowable<V> observable;

            // attempt to retrieve the weakly held observable
            if (_weakCache.containsKey(key)) {
                weakObservable = _weakCache.get(key);
                observable = weakObservable.get();

                if (observable != null) {
                    // we already have a cached observable bound to this key
                    return observable;
                }
            }

            // we do not have an observable for the key, escalate the lock
            _readLock.unlock();
            _writeLock.lock();

            try {
                // recheck the observable since we had to retake the lock
                if (_weakCache.containsKey(key)) {
                    weakObservable = _weakCache.get(key);
                    observable = weakObservable.get();

                    if (observable != null) {
                        // we found a hit this time around, return the hit
                        return observable;
                    }
                    else {
                        // the target of the weak source should have already been cleared by the
                        // garbage collector since the source is retained by the cached observable
                        _weakSources.remove(key);
                    }
                }

                Function<K, Single<V>> faultHandler = _faultHandler;

                if (_multiFaultHandler != null) {
                    faultHandler = new Function<K, Single<V>>() {
                        @Override
                        public Single<V> apply(K k) throws Exception {
                            return _multiFaultHandler.apply(Arrays.asList(k)).map(new Function<List<V>, V>() {
                                @Override
                                public V apply(List<V> vs) throws Exception {

                                    if (vs.size() != 1) {
                                        throw new IllegalStateException("Multifault handler returned result of incorrect size.") ;
                                    }

                                    return vs.get(0);
                                }
                            });
                        }
                    };
                }

                // no observable was found in the cache, create a new binding
                observable = Flowable.create(new OnSubscribeAttach(key, faultHandler), BackpressureStrategy.LATEST);

                _weakCache.put(key, new WeakReference<>(observable));
            }
            finally {
                _readLock.lock();
                _writeLock.unlock();
            }

            return observable;
        }
        finally {
            _readLock.unlock();
        }
    }

    /**
     * Clear all internal caches for this map.
     * onComplete() will be called for all sources that are still alive.
     * The map will be empty after this call returns.
     */
    public void clearAndDetachAll()
    {
        List<Processor<V, V>> lingeringProcessors = new ArrayList<>(_weakSources.size());
        _writeLock.lock();
        try {
            _cache.clear();
            for (WeakReference<Processor<V, V>> weakProcessors : _weakSources.values()) {
                Processor<V,V> processor = weakProcessors.get();
                if (processor != null) {
                    lingeringProcessors.add(processor);
                }
            }
            _weakSources.clear();
            _weakCache.clear();
        }
        finally {
            _writeLock.unlock();
            for (Processor<V,V> processor : lingeringProcessors) {
                processor.onComplete();
            }
        }
    }
}
