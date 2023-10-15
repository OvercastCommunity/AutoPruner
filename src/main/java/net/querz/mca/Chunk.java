package net.querz.mca;

import net.querz.nbt.io.NBTDeserializer;
import net.querz.nbt.io.NBTSerializer;
import net.querz.nbt.io.NamedTag;
import net.querz.nbt.tag.CompoundTag;
import net.querz.nbt.tag.ListTag;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;

public class Chunk implements Iterable<Section> {

  private final int lastMCAUpdate;
  private final Map<Integer, Section> sections = new TreeMap<>();
  private CompoundTag data;
  private int dataVersion;
  private long lastUpdate;
  private long inhabitedTime;
  private byte lightPopulated;
  private byte terrainPopulated;
  private byte v;
  private byte[] biomes;
  private int[] heightMaps;
  private ListTag entities;
  private ListTag tileEntities;
  private ListTag tileTicks;

  Chunk(int lastMCAUpdate) {
    this.lastMCAUpdate = lastMCAUpdate;
  }

  private void initReferences() {
    if (data == null) {
      throw new NullPointerException("data cannot be null");
    }

    CompoundTag level;
    if ((level = data.getCompoundTag("Level")) == null) {
      throw new IllegalArgumentException("data does not contain \"Level\" tag");
    }
    dataVersion = data.getInt("DataVersion");
    inhabitedTime = level.getLong("InhabitedTime");
    lastUpdate = level.getLong("LastUpdate");
    lightPopulated = level.getByte("LightPopulated");
    terrainPopulated = level.getByte("TerrainPopulated");
    v = level.getByte("V");
    biomes = level.getByteArray("Biomes");
    heightMaps = level.getIntArray("Heightmaps");

    entities = level.containsKey("Entities") ? level.getListTag("Entities") : null;
    tileEntities = level.containsKey("TileEntities") ? level.getListTag("TileEntities") : null;
    tileTicks = level.containsKey("TileTicks") ? level.getListTag("TileTicks") : null;
    if (level.containsKey("Sections")) {
      ListTag<?> sourceSections = level.getListTag("Sections");
      try {
        for (CompoundTag section : sourceSections.asCompoundTagList()) {
          int sectionIndex = section.getNumber("Y").byteValue();
          Section newSection = new Section(section);
          sections.put(sectionIndex, newSection);
        }
      } catch (Exception e) {
        // ignore, no sections
      }
    }
  }

  /**
   * Serializes this chunk to a <code>RandomAccessFile</code>.
   *
   * @param raf  The RandomAccessFile to be written to.
   * @param xPos The x-coordinate of the chunk.
   * @param zPos The z-coodrinate of the chunk.
   * @return The amount of bytes written to the RandomAccessFile.
   * @throws UnsupportedOperationException When something went wrong during writing.
   * @throws IOException                   When something went wrong during writing.
   */
  public int serialize(RandomAccessFile raf, int xPos, int zPos) throws IOException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream(4096);
    try (BufferedOutputStream nbtOut = new BufferedOutputStream(CompressionType.ZLIB.compress(baos))) {
      new NBTSerializer(false).toStream(new NamedTag(null, updateHandle(xPos, zPos)), nbtOut);
    }
    byte[] rawData = baos.toByteArray();
    raf.writeInt(rawData.length + 1); // including the byte to store the compression type
    raf.writeByte(CompressionType.ZLIB.getID());
    raf.write(rawData);
    return rawData.length + 5;
  }

  /**
   * Reads chunk data from a RandomAccessFile. The RandomAccessFile must already be at the correct position.
   *
   * @param raf The RandomAccessFile to read the chunk data from.
   * @throws IOException When something went wrong during reading.
   */
  public void deserialize(RandomAccessFile raf) throws IOException {
    byte compressionTypeByte = raf.readByte();
    CompressionType compressionType = CompressionType.getFromID(compressionTypeByte);
    if (compressionType == null) {
      throw new IOException("invalid compression type " + compressionTypeByte);
    }
    BufferedInputStream dis = new BufferedInputStream(compressionType.decompress(new FileInputStream(raf.getFD())));
    NamedTag tag = new NBTDeserializer(false).fromStream(dis);
    if (tag != null && tag.getTag() instanceof CompoundTag) {
      data = (CompoundTag) tag.getTag();
      initReferences();
    } else {
      throw new IOException("invalid data tag: " + (tag == null ? "null" : tag.getClass().getName()));
    }
  }

  /**
   * @return whether the chunks has a biome other than plains
   */
  public boolean hasSpecialBiomes() {
    for (byte b : biomes) {
      if (b != 1) {
        return true;
      }
    }
    return false;
  }

  /**
   * @return The timestamp when this region file was last updated in seconds since 1970-01-01.
   */
  public int getLastMCAUpdate() {
    return lastMCAUpdate;
  }

  /**
   * Fetches the section at the given y-coordinate.
   *
   * @param sectionY The y-coordinate of the section in this chunk ranging from 0 to 15.
   * @return The Section.
   */
  public Section getSection(int sectionY) {
    return sections.get(sectionY);
  }

  /**
   * @return The entities of this chunk.
   */
  public ListTag<CompoundTag> getEntities() {
    return entities;
  }

  /**
   * @return The tile entities of this chunk.
   */
  public ListTag<CompoundTag> getTileEntities() {
    return tileEntities;
  }

  public CompoundTag updateHandle(int xPos, int zPos) {
    data.putInt("DataVersion", dataVersion);
    CompoundTag level = data.getCompoundTag("Level");
    level.putInt("xPos", xPos);
    level.putInt("zPos", zPos);
    level.putLong("LastUpdate", lastUpdate);
    level.putLong("InhabitedTime", inhabitedTime);
    level.putByte("LightPopulated", lightPopulated);
    level.putByte("TerrainPopulated", terrainPopulated);
    level.putByte("V", v);
    if (biomes != null) {
      level.putByteArray("Biomes", biomes);
    }
    if (heightMaps != null) {
      level.putIntArray("Heightmaps", heightMaps);
    }
    if (entities != null) {
      level.put("Entities", entities);
    }
    if (tileEntities != null) {
      level.put("TileEntities", tileEntities);
    }
    if (tileTicks != null) {
      level.put("TileTicks", tileTicks);
    }
    ListTag<CompoundTag> sections = new ListTag<>(CompoundTag.class);
    for (Section section : this.sections.values()) {
      if (section != null && !section.isEmpty()) {
        sections.add(section.updateHandle());
      }
    }
    level.put("Sections", sections);
    return data;
  }

  @Override
  public Iterator<Section> iterator() {
    return sections.values().iterator();
  }
}
