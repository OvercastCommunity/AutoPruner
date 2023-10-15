package net.querz.nbt.tag;

import net.querz.io.MaxDepthIO;

import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class CompoundTag extends Tag<Map<String, Tag<?>>>
    implements Iterable<Map.Entry<String, Tag<?>>>, Comparable<CompoundTag>, MaxDepthIO {

  public static final byte ID = 10;

  public CompoundTag() {
    super(createEmptyValue());
  }

  public CompoundTag(int initialCapacity) {
    super(new HashMap<>(initialCapacity));
  }

  private static Map<String, Tag<?>> createEmptyValue() {
    return new HashMap<>(8);
  }

  @Override
  public byte getID() {
    return ID;
  }

  public int size() {
    return getValue().size();
  }

  public boolean containsKey(String key) {
    return getValue().containsKey(key);
  }

  public Collection<Tag<?>> values() {
    return getValue().values();
  }

  public Set<Map.Entry<String, Tag<?>>> entrySet() {
    return new NonNullEntrySet<>(getValue().entrySet());
  }

  @Override
  public Iterator<Map.Entry<String, Tag<?>>> iterator() {
    return entrySet().iterator();
  }

  public <C extends Tag<?>> C get(String key, Class<C> type) {
    Tag<?> t = getValue().get(key);
    if (t != null) {
      return type.cast(t);
    }
    return null;
  }

  public Tag<?> get(String key) {
    return getValue().get(key);
  }

  public NumberTag<?> getNumberTag(String key) {
    return (NumberTag<?>) getValue().get(key);
  }

  public Number getNumber(String key) {
    return getNumberTag(key).getValue();
  }

  public ByteTag getByteTag(String key) {
    return get(key, ByteTag.class);
  }

  public IntTag getIntTag(String key) {
    return get(key, IntTag.class);
  }

  public LongTag getLongTag(String key) {
    return get(key, LongTag.class);
  }

  public ByteArrayTag getByteArrayTag(String key) {
    return get(key, ByteArrayTag.class);
  }

  public IntArrayTag getIntArrayTag(String key) {
    return get(key, IntArrayTag.class);
  }

  public ListTag<?> getListTag(String key) {
    return get(key, ListTag.class);
  }

  public CompoundTag getCompoundTag(String key) {
    return get(key, CompoundTag.class);
  }

  public byte getByte(String key) {
    ByteTag t = getByteTag(key);
    return t == null ? ByteTag.ZERO_VALUE : t.asByte();
  }

  public int getInt(String key) {
    IntTag t = getIntTag(key);
    return t == null ? IntTag.ZERO_VALUE : t.asInt();
  }

  public long getLong(String key) {
    LongTag t = getLongTag(key);
    return t == null ? LongTag.ZERO_VALUE : t.asLong();
  }

  public byte[] getByteArray(String key) {
    ByteArrayTag t = getByteArrayTag(key);
    return t == null ? ByteArrayTag.ZERO_VALUE : t.getValue();
  }

  public int[] getIntArray(String key) {
    IntArrayTag t = getIntArrayTag(key);
    return t == null ? IntArrayTag.ZERO_VALUE : t.getValue();
  }

  public Tag<?> put(String key, Tag<?> tag) {
    return getValue().put(Objects.requireNonNull(key), Objects.requireNonNull(tag));
  }

  public Tag<?> putByte(String key, byte value) {
    return put(key, new ByteTag(value));
  }

  public Tag<?> putInt(String key, int value) {
    return put(key, new IntTag(value));
  }

  public Tag<?> putLong(String key, long value) {
    return put(key, new LongTag(value));
  }

  public Tag<?> putByteArray(String key, byte[] value) {
    return put(key, new ByteArrayTag(value));
  }

  public Tag<?> putIntArray(String key, int[] value) {
    return put(key, new IntArrayTag(value));
  }

  @Override
  public String valueToString(int maxDepth) {
    StringBuilder sb = new StringBuilder("{");
    boolean first = true;
    for (Map.Entry<String, Tag<?>> e : getValue().entrySet()) {
      sb.append(first ? "" : ",")
          .append(escapeString(e.getKey(), false)).append(":")
          .append(e.getValue().toString(decrementMaxDepth(maxDepth)));
      first = false;
    }
    sb.append("}");
    return sb.toString();
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!super.equals(other) || size() != ((CompoundTag) other).size()) {
      return false;
    }
    for (Map.Entry<String, Tag<?>> e : getValue().entrySet()) {
      Tag<?> v;
      if ((v = ((CompoundTag) other).get(e.getKey())) == null || !e.getValue().equals(v)) {
        return false;
      }
    }
    return true;
  }

  @Override
  public int compareTo(CompoundTag o) {
    return Integer.compare(size(), o.getValue().size());
  }

  @Override
  public CompoundTag clone() {
    // Choose initial capacity based on default load factor (0.75) so all entries fit in map without resizing
    CompoundTag copy = new CompoundTag((int) Math.ceil(getValue().size() / 0.75f));
    for (Map.Entry<String, Tag<?>> e : getValue().entrySet()) {
      copy.put(e.getKey(), e.getValue().clone());
    }
    return copy;
  }
}
