package ini.trakem2.utils;

import java.util.HashMap;
import java.util.Map;

/**
 * An extended {@link HashMap} that throws {@link UnsupportedOperationException}
 * for calls to {@link HashMap#get(Object)}, {@link HashMap#remove(Object)},
 * {@link HashMap#containsKey(Object)} and {@link HashMap#containsValue(Object)}.
 * This class offers versions of these four methods but requiring typed arguments.
 * 
 * @author Albert Cardona
 *
 * @param <K>
 * @param <V>
 */
public class TypedHashMap<K,V> extends HashMap<K,V>
{
	private static final long serialVersionUID = -7817318751687157665L;
	
	public TypedHashMap() {
		super();
	}
	
	public TypedHashMap(final int initialCapacity) {
		super(initialCapacity);
	}
	
	public TypedHashMap(final Map<? extends K, ? extends V> map) {
		super(map);
	}
	
	public TypedHashMap(final int initialCapacity, final float loadFactor) {
		super(initialCapacity, loadFactor);
	}

	/** Typed version of {@link HashMap#get(Object). */
	public V getValue(final K key) {
		return super.get(key);
	}
	
	/**
	 * @throws {@link UnsupportedOperationException}
	 */
	@Override
	public V get(final Object key) {
		throw new UnsupportedOperationException();
	}

	/** Typed version of {@link HashMap#remove(Object). */
	public V removeEntry(final K key) {
		return super.remove(key);
	}
	
	/**
	 * @throws {@link UnsupportedOperationException}
	 */
	@Override
	public V remove(final Object key) {
		throw new UnsupportedOperationException();
	}
	
	/** Typed version of {@link HashMap#containsKey(Object). */
	public boolean hasKey(final K key) {
		return super.containsKey(key);
	}
	
	/**
	 * @throws {@link UnsupportedOperationException}
	 */
	@Override
	public boolean containsKey(final Object key) {
		throw new UnsupportedOperationException();
	}
	
	/** Typed version of {@link HashMap#containsValue(Object). */
	public boolean hasValue(final V value) {
		return super.containsValue(value);
	}
	
	/**
	 * @throws {@link UnsupportedOperationException}
	 */
	@Override
	public boolean containsValue(final Object value) {
		throw new UnsupportedOperationException();
	}
}
