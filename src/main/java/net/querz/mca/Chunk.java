package net.querz.mca;

import net.querz.nbt.io.NBTDeserializer;
import net.querz.nbt.io.NBTSerializer;
import net.querz.nbt.io.NamedTag;
import net.querz.nbt.tag.CompoundTag;
import net.querz.nbt.tag.ListTag;
import net.querz.nbt.tag.StringTag;
import net.querz.nbt.tag.Tag;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * A single chunk within a region (.mca) file.
 *
 * <p>This class is deliberately format-agnostic. Minecraft has changed the chunk NBT layout
 * several times, so rather than parsing the chunk into typed fields and rebuilding it on write
 * (which silently drops any tag the parser doesn't know about), the raw root {@link CompoundTag}
 * is kept verbatim and written back unchanged. Only the handful of fields needed for the
 * keep/prune decision are read, dispatched by {@code DataVersion}:</p>
 *
 * <ul>
 *   <li><b>Legacy</b> (1.12 and earlier): {@code Level} compound, numeric {@code Blocks}/{@code Add}
 *       arrays, byte biomes, inline {@code Entities}.</li>
 *   <li><b>Flattened</b> (1.13 to 1.17): {@code Level} compound, block palette + {@code BlockStates},
 *       int biomes, inline {@code Entities} (moved out at 1.17).</li>
 *   <li><b>Modern</b> (1.18 and later): no {@code Level} compound, per-section {@code block_states}
 *       and {@code biomes} palettes, entities in a separate {@code entities} region.</li>
 * </ul>
 */
public class Chunk {

  /** 1.13 (17w47a): block sections switched to a palette + packed {@code BlockStates}. */
  private static final int DV_FLATTENING = 1451;
  /** 1.17 (20w45a): entities moved out of region chunks into a separate {@code entities} region. */
  private static final int DV_ENTITIES_SEPARATED = 2681;
  /** 1.18 (21w43a): {@code Level} compound removed; biomes stored per-section as a palette. */
  private static final int DV_ROOT_LAYOUT = 2844;

  private static final byte LEGACY_PLAINS_BIOME = 1;
  private static final String DEFAULT_BIOME = "minecraft:plains";

  private final int lastMCAUpdate;
  private CompoundTag data;
  private int dataVersion;
  private boolean changesMade = false;

  Chunk(int lastMCAUpdate) {
    this.lastMCAUpdate = lastMCAUpdate;
  }

  /** Wraps an already-parsed chunk tag. Intended for tests. */
  Chunk(CompoundTag data) {
    this.lastMCAUpdate = 0;
    this.data = data;
    init();
  }

  private void init() {
    if (data == null) {
      throw new NullPointerException("data cannot be null");
    }
    dataVersion = data.getInt("DataVersion");
    // Pre-flattening sections store a full block array per section, so dropping the empty ones
    // is a meaningful space saving. Newer formats store empty sections as a one-entry air palette
    // (a few bytes), so they are left untouched to avoid disturbing lighting/other data.
    if (!isFlattened()) {
      stripEmptyLegacySections();
    }
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
    readTag(dis);
  }

  /**
   * Reads chunk data from a stream positioned at the start of the chunk's compression-type byte.
   *
   * @param byteArrayInputStream The stream to read the chunk data from.
   * @throws IOException When something went wrong during reading.
   */
  public void deserialize(ByteArrayInputStream byteArrayInputStream) throws IOException {
    byte compressionTypeByte = (byte) byteArrayInputStream.read();
    CompressionType compressionType = CompressionType.getFromID(compressionTypeByte);
    if (compressionType == null) {
      throw new IOException("invalid compression type " + compressionTypeByte);
    }
    BufferedInputStream dis = new BufferedInputStream(compressionType.decompress(byteArrayInputStream));
    readTag(dis);
  }

  private void readTag(BufferedInputStream dis) throws IOException {
    NamedTag tag = new NBTDeserializer(false).fromStream(dis);
    if (tag != null && tag.getTag() instanceof CompoundTag) {
      data = (CompoundTag) tag.getTag();
      init();
    } else {
      throw new IOException("invalid data tag: " + (tag == null ? "null" : tag.getClass().getName()));
    }
  }

  /**
   * Serializes this chunk to a {@code RandomAccessFile}. The raw tag is written back unchanged
   * (apart from legacy empty-section stripping applied during reading), preserving every field
   * regardless of the chunk's version.
   *
   * @param raf The RandomAccessFile to be written to.
   * @return The amount of bytes written to the RandomAccessFile.
   * @throws IOException When something went wrong during writing.
   */
  public int serialize(RandomAccessFile raf) throws IOException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream(4096);
    try (BufferedOutputStream nbtOut = new BufferedOutputStream(CompressionType.ZLIB.compress(baos))) {
      new NBTSerializer(false).toStream(new NamedTag(null, data), nbtOut);
    }
    byte[] rawData = baos.toByteArray();
    raf.writeInt(rawData.length + 1); // including the byte to store the compression type
    raf.writeByte(CompressionType.ZLIB.getID());
    raf.write(rawData);
    return rawData.length + 5;
  }

  // ---------------------------------------------------------------------------------------------
  // Pruning decision
  // ---------------------------------------------------------------------------------------------

  /**
   * Determines whether this chunk holds anything worth keeping.
   *
   * @param entityChunk The chunk at the same index in the sibling {@code entities} region
   *                    (1.17+, where entities live in a separate file), or {@code null}.
   * @return {@code true} if the chunk should be kept, {@code false} if it can be pruned.
   */
  public boolean hasContent(Chunk entityChunk) {
    // A chunk from a separate "entities" region: keep it iff it actually stores entities.
    if (isEntityChunk()) {
      return entitiesPresent(data);
    }
    // Anything we don't recognise as a block chunk (e.g. POI data) is left untouched.
    if (!isBlockChunk()) {
      return true;
    }
    if (hasBlockEntities()) {
      return true;
    }
    if (hasBlocks()) {
      return true;
    }
    if (hasSpecialBiomes()) {
      return true;
    }
    if (!entitiesStoredSeparately()) {
      return entitiesPresent(fields());
    }
    return entityChunk != null && entityChunk.isEntityChunk() && entitiesPresent(entityChunk.data);
  }

  /** @return whether this is an entity-storage chunk from a separate {@code entities} region file. */
  boolean isEntityChunk() {
    return data.containsKey("Entities") && data.containsKey("Position")
        && !data.containsKey("Sections") && !data.containsKey("sections")
        && !data.containsKey("Level");
  }

  /**
   * @return whether this looks like a normal block chunk we know how to evaluate. The section
   * list lives at {@code sections} (root) in 1.18+ and {@code Level.Sections} before that;
   * anything else (POI data, custom structures) is treated as unknown and never pruned.
   */
  private boolean isBlockChunk() {
    if (usesRootLayout()) {
      return childList(data, "sections") != null;
    }
    CompoundTag level = data.getCompoundTag("Level");
    return level != null && childList(level, "Sections") != null;
  }

  private boolean entitiesPresent(CompoundTag container) {
    return listSize(container, "Entities") > 0;
  }

  private boolean hasBlockEntities() {
    return listSize(fields(), usesRootLayout() ? "block_entities" : "TileEntities") > 0;
  }

  private boolean hasBlocks() {
    ListTag<?> sections = childList(fields(), usesRootLayout() ? "sections" : "Sections");
    if (sections == null) {
      return false;
    }
    for (Tag<?> t : sections) {
      if (!(t instanceof CompoundTag)) {
        continue;
      }
      CompoundTag section = (CompoundTag) t;
      boolean nonEmpty = isFlattened() ? sectionHasPaletteBlocks(section) : sectionHasNumericBlocks(section);
      if (nonEmpty) {
        return true;
      }
    }
    return false;
  }

  private boolean sectionHasPaletteBlocks(CompoundTag section) {
    ListTag<?> palette;
    if (usesRootLayout()) {
      CompoundTag blockStates = section.getCompoundTag("block_states");
      palette = blockStates == null ? null : childList(blockStates, "palette");
    } else {
      palette = childList(section, "Palette");
    }
    return paletteHasNonAir(palette);
  }

  /**
   * @return whether the chunk has any biome other than the default plains, used to preserve
   * intentionally biome-painted chunks even when they hold no blocks.
   */
  private boolean hasSpecialBiomes() {
    if (usesRootLayout()) {
      return modernHasSpecialBiomes();
    }
    CompoundTag level = fields();
    if (isFlattened()) {
      for (int b : level.getIntArray("Biomes")) {
        if (b != LEGACY_PLAINS_BIOME && b >= 0) {
          return true;
        }
      }
      return false;
    }
    for (byte b : level.getByteArray("Biomes")) {
      if (b != LEGACY_PLAINS_BIOME) {
        return true;
      }
    }
    return false;
  }

  private boolean modernHasSpecialBiomes() {
    ListTag<?> sections = childList(data, "sections");
    if (sections == null) {
      return false;
    }
    for (Tag<?> t : sections) {
      if (!(t instanceof CompoundTag)) {
        continue;
      }
      CompoundTag biomes = ((CompoundTag) t).getCompoundTag("biomes");
      ListTag<?> palette = biomes == null ? null : childList(biomes, "palette");
      if (palette == null) {
        continue;
      }
      for (Tag<?> entry : palette) {
        if (!(entry instanceof StringTag) || !DEFAULT_BIOME.equals(((StringTag) entry).getValue())) {
          return true;
        }
      }
    }
    return false;
  }

  /** Replaces the legacy {@code Sections} list with only its non-empty sections, leaving all else raw. */
  private void stripEmptyLegacySections() {
    CompoundTag level = fields();
    ListTag<?> sections = childList(level, "Sections");
    if (sections == null) {
      return;
    }
    ListTag<CompoundTag> kept = new ListTag<>(CompoundTag.class);
    boolean removedAny = false;
    for (Tag<?> t : sections) {
      if (t instanceof CompoundTag && sectionHasNumericBlocks((CompoundTag) t)) {
        kept.add((CompoundTag) t);
      } else {
        removedAny = true;
      }
    }
    if (removedAny) {
      level.put("Sections", kept);
      changesMade = true;
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Format predicates & helpers
  // ---------------------------------------------------------------------------------------------

  private boolean isFlattened() {
    return dataVersion >= DV_FLATTENING;
  }

  private boolean usesRootLayout() {
    return dataVersion >= DV_ROOT_LAYOUT;
  }

  private boolean entitiesStoredSeparately() {
    return dataVersion >= DV_ENTITIES_SEPARATED;
  }

  /** The compound holding chunk fields: the root itself in 1.18+, otherwise the {@code Level} child. */
  private CompoundTag fields() {
    if (usesRootLayout()) {
      return data;
    }
    CompoundTag level = data.getCompoundTag("Level");
    return level != null ? level : data;
  }

  private static boolean sectionHasNumericBlocks(CompoundTag section) {
    return anyNonZero(section.getByteArray("Blocks")) || anyNonZero(section.getByteArray("Add"));
  }

  private static boolean paletteHasNonAir(ListTag<?> palette) {
    if (palette == null) {
      return false;
    }
    for (Tag<?> entry : palette) {
      if (!(entry instanceof CompoundTag)) {
        return true; // unexpected shape: keep to be safe
      }
      StringTag name = ((CompoundTag) entry).get("Name", StringTag.class);
      if (name == null || !isAir(name.getValue())) {
        return true;
      }
    }
    return false;
  }

  private static boolean isAir(String blockName) {
    return blockName.equals("minecraft:air")
        || blockName.equals("minecraft:cave_air")
        || blockName.equals("minecraft:void_air");
  }

  private static boolean anyNonZero(byte[] array) {
    for (byte b : array) {
      if (b != 0) {
        return true;
      }
    }
    return false;
  }

  private static ListTag<?> childList(CompoundTag compound, String key) {
    if (compound == null || !compound.containsKey(key)) {
      return null;
    }
    Tag<?> t = compound.get(key);
    return t instanceof ListTag ? (ListTag<?>) t : null;
  }

  private static int listSize(CompoundTag compound, String key) {
    ListTag<?> list = childList(compound, key);
    return list == null ? 0 : list.size();
  }

  /**
   * @return The timestamp when this region file was last updated in seconds since 1970-01-01.
   */
  public int getLastMCAUpdate() {
    return lastMCAUpdate;
  }

  /** @return whether reading this chunk mutated it (legacy empty-section stripping). */
  public boolean changesMade() {
    return changesMade;
  }

  /** @return this chunk's {@code DataVersion}, or 0 for pre-1.9 chunks that predate the field. */
  public int getDataVersion() {
    return dataVersion;
  }

  /** A short human label for this chunk's format, e.g. {@code "DataVersion 4556, modern 1.18+"}. */
  public String describeVersion() {
    return "DataVersion " + dataVersion + ", " + versionEra();
  }

  /** The format era this chunk belongs to, used to group files in a prune summary. */
  public String versionEra() {
    if (usesRootLayout()) {
      return "modern 1.18+";
    } else if (isFlattened()) {
      return "flattened 1.13-1.17";
    } else {
      return "legacy 1.12 and earlier";
    }
  }
}
