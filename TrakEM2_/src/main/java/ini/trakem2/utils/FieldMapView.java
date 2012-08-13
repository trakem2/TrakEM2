package ini.trakem2.utils;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Classes extending this class acquire a read-only Map interface to access its declared fields 
 * via the reflection API provided by {@link Class#getDeclaredFields()} and {@link Field}.
 * Fields of superclasses are not included.
 */
public class FieldMapView implements Map<String,String>
{
	@Override
	public int size() {
		return getClass().getDeclaredFields().length;
	}

	@Override
	public boolean isEmpty() {
		return 0 != size();
	}

	/** Linear search.
	 * @param key A {@link String}. Can be null. Tests for field names with {@link Object#equals(Object)}.
	 */
	@Override
	public boolean containsKey(final Object key) {
		if (null == key) return false;
		for (final Field field : getClass().getDeclaredFields()) {
			if (field.getName().equals(key)) return true;
		}
		return false;
	}

	/** Linear search.
	 * @param value A {@link String}. Can be null. Tests for field values with {@link Object#equals(Object)}.
	 */
	@Override
	public boolean containsValue(final Object value) {
		if (null == value) return false;
		for (final Field field : getClass().getDeclaredFields()) {
			field.setAccessible(true);
			try {
				if (field.get(this).equals(value)) return true;
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		}
		return false;
	}

	/** Linear search.
	 * @param key A {@link String}. Can be null. Tests for field names with {@link Object#equals(Object)}.
	 * @return A {@link String} representation of the field named by {@param key}.
	 */
	@Override
	public String get(final Object key) {
		if (null == key) return null;
		for (final Field field : getClass().getDeclaredFields()) {
			if (field.getName().equals(key)) {
				field.setAccessible(true);
				try {
					return field.get(this).toString();
				} catch (Exception e) {
					e.printStackTrace();
					return null;
				}
			}
		}
		return null;
	}

	/** 
	 * @throws UnsupportedOperationException
	 */
	@Override
	public String put(String key, String value) {
		throw new UnsupportedOperationException();
	}

	/** 
	 * @throws UnsupportedOperationException
	 */
	@Override
	public String remove(Object key) {
		throw new UnsupportedOperationException();
	}

	/** 
	 * @throws UnsupportedOperationException
	 */
	@Override
	public void putAll(Map<? extends String, ? extends String> m) {
		throw new UnsupportedOperationException();
	}

	/** 
	 * @throws UnsupportedOperationException
	 */
	@Override
	public void clear() {
		throw new UnsupportedOperationException();
	}

	/**
	 * @return An unmodifiable {@link Set} as created by {@link Collections#unmodifiableSet(Set)}.
	 */
	@Override
	public Set<String> keySet() {
		final HashSet<String> hs = new HashSet<String>(size());
		for (final Field field : getClass().getDeclaredFields()) {
			hs.add(field.getName());
		}
		return Collections.unmodifiableSet(hs);
	}

	/**
	 * @return An unmodifiable {@link List} as created by {@link Collections#unmodifiableList(java.util.List)}.
	 */
	@Override
	public Collection<String> values() {
		final ArrayList<String> vals = new ArrayList<String>(size());
		for (final Field field : getClass().getDeclaredFields()) {
			field.setAccessible(true);
			try {
				vals.add(field.get(this).toString());
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		return Collections.unmodifiableList(vals);
	}

	/**
	 * @return An unmodifiable {@link Set} as created by {@link Collections#unmodifiableSet(Set)} of unmodifiable entries.
	 */
	@Override
	public Set<Map.Entry<String, String>> entrySet() {
		final HashSet<Map.Entry<String,String>> entries = new HashSet<Map.Entry<String,String>>(size());
		for (final Field field : getClass().getDeclaredFields()) {
			field.setAccessible(true);
			entries.add(new Map.Entry<String,String>() {
				@Override
				public String getKey() {
					return field.getName();
				}
				@Override
				public String getValue() {
					try {
						return field.get(FieldMapView.this).toString();
					} catch (Exception e) {
						throw new RuntimeException(e);
					}
				}
				@Override
				public String setValue(String value) {
					throw new UnsupportedOperationException();
				}
			});
		}
		return Collections.unmodifiableSet(entries);
	}
}
