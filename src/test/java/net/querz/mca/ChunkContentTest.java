package net.querz.mca;

import net.querz.nbt.tag.CompoundTag;
import net.querz.nbt.tag.ListTag;
import net.querz.nbt.tag.StringTag;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Verifies the version-aware keep/prune decision in {@link Chunk#hasContent(Chunk)} for each
 * chunk-format era, using hand-built NBT so the logic is exercised without real region files.
 */
public class ChunkContentTest {

  // DataVersions representative of each era.
  private static final int DV_LEGACY = 0;        // 1.8 (no DataVersion field)
  private static final int DV_1_14 = 1976;       // flattened, inline entities
  private static final int DV_1_17 = 2730;       // flattened, separated entities
  private static final int DV_1_21 = 4556;       // modern (matches the sample world)

  // ---------------------------------------------------------------------------------------------
  // Modern (1.18+)
  // ---------------------------------------------------------------------------------------------

  @Test
  public void modernEmptyChunkIsPruned() {
    Chunk chunk = new Chunk(modernChunk(modernSection(-4, "minecraft:air"), modernSection(0, "minecraft:air")));
    assertFalse(chunk.hasContent(null));
  }

  @Test
  public void modernChunkWithBlocksIsKept() {
    Chunk chunk = new Chunk(modernChunk(modernSection(0, "minecraft:air", "minecraft:stone")));
    assertTrue(chunk.hasContent(null));
  }

  @Test
  public void modernChunkWithBlockEntitiesIsKept() {
    CompoundTag data = modernChunk(modernSection(0, "minecraft:air"));
    ListTag<CompoundTag> blockEntities = new ListTag<>(CompoundTag.class);
    blockEntities.add(named("minecraft:mob_spawner"));
    data.put("block_entities", blockEntities);
    assertTrue(new Chunk(data).hasContent(null));
  }

  @Test
  public void modernChunkWithSpecialBiomeIsKept() {
    CompoundTag section = modernSection(0, "minecraft:air");
    setBiome(section, "minecraft:the_void");
    assertTrue(new Chunk(modernChunk(section)).hasContent(null));
  }

  @Test
  public void modernEmptyChunkWithSeparateEntitiesIsKept() {
    Chunk blockChunk = new Chunk(modernChunk(modernSection(0, "minecraft:air")));
    Chunk entityChunk = new Chunk(entityChunk(DV_1_21, named("minecraft:armor_stand")));
    assertTrue(blockChunk.hasContent(entityChunk));
  }

  @Test
  public void emptyEntityChunkIsPruned() {
    Chunk entityChunk = new Chunk(entityChunk(DV_1_21));
    assertTrue(entityChunk.isEntityChunk());
    assertFalse(entityChunk.hasContent(null));
  }

  @Test
  public void populatedEntityChunkIsKept() {
    Chunk entityChunk = new Chunk(entityChunk(DV_1_21, named("minecraft:item_frame")));
    assertTrue(entityChunk.hasContent(null));
  }

  // ---------------------------------------------------------------------------------------------
  // Modern, custom-floor dimensions (e.g. PGM dimensions that set min_y=0)
  //
  // A custom dimension can move the world floor (min_y) off the vanilla -64, so a chunk's sections
  // start at Y=0 with no negative-Y sections at all. The keep/prune decision inspects each
  // section's block palette and never reads the section Y, so it must behave the same regardless
  // of where the floor sits. (min_y itself lives in the dimension type, not the region file, so it
  // is never touched.)
  // ---------------------------------------------------------------------------------------------

  @Test
  public void modernMinY0ChunkWithBlocksIsKept() {
    Chunk chunk = new Chunk(modernChunk(
        modernSection(0, "minecraft:air", "minecraft:stone"),
        modernSection(1, "minecraft:air"),
        modernSection(2, "minecraft:air")));
    assertTrue(chunk.hasContent(null));
  }

  @Test
  public void modernMinY0EmptyChunkIsPruned() {
    Chunk chunk = new Chunk(modernChunk(
        modernSection(0, "minecraft:air"),
        modernSection(1, "minecraft:air"),
        modernSection(2, "minecraft:air")));
    assertFalse(chunk.hasContent(null));
  }

  @Test
  public void modernPruneDecisionIsIndependentOfSectionY() {
    // The same single-stone section keeps the chunk no matter which Y it claims, proving the
    // decision reads the block palette rather than any assumed Y range.
    for (int y : new int[]{-4, 0, 4, 19}) {
      Chunk chunk = new Chunk(modernChunk(modernSection(y, "minecraft:air", "minecraft:stone")));
      assertTrue("block at section Y=" + y + " should be kept", chunk.hasContent(null));
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Flattened (1.13 - 1.17)
  // ---------------------------------------------------------------------------------------------

  @Test
  public void flattenedEmptyChunkIsPruned() {
    Chunk chunk = new Chunk(legacyChunk(DV_1_14, paletteSection("minecraft:air")));
    assertFalse(chunk.hasContent(null));
  }

  @Test
  public void flattenedChunkWithBlocksIsKept() {
    Chunk chunk = new Chunk(legacyChunk(DV_1_14, paletteSection("minecraft:air", "minecraft:dirt")));
    assertTrue(chunk.hasContent(null));
  }

  @Test
  public void flattenedInlineEntitiesAreKept() {
    CompoundTag data = legacyChunk(DV_1_14, paletteSection("minecraft:air"));
    addInlineEntities(data, named("minecraft:cow"));
    assertTrue(new Chunk(data).hasContent(null));
  }

  @Test
  public void flattenedSpecialBiomeIsKept() {
    CompoundTag data = legacyChunk(DV_1_14, paletteSection("minecraft:air"));
    level(data).putIntArray("Biomes", new int[]{1, 1, 7, 1}); // 7 = river
    assertTrue(new Chunk(data).hasContent(null));
  }

  @Test
  public void flattened1_17EntitiesComeFromSiblingRegion() {
    // Inline Entities must be ignored once entities are separated; only the sibling counts.
    CompoundTag blockData = legacyChunk(DV_1_17, paletteSection("minecraft:air"));
    addInlineEntities(blockData, named("minecraft:ignored"));
    Chunk blockChunk = new Chunk(blockData);
    assertFalse("inline entities should be ignored at 1.17", blockChunk.hasContent(null));

    Chunk entityChunk = new Chunk(entityChunk(DV_1_17, named("minecraft:painting")));
    assertTrue(blockChunk.hasContent(entityChunk));
  }

  // ---------------------------------------------------------------------------------------------
  // Legacy (<= 1.12)
  // ---------------------------------------------------------------------------------------------

  @Test
  public void legacyEmptyChunkIsPrunedAndSectionsStripped() {
    CompoundTag data = legacyChunk(DV_LEGACY, numericSection(new byte[4096]));
    Chunk chunk = new Chunk(data);
    assertFalse(chunk.hasContent(null));
    assertTrue("empty legacy sections should be stripped on read", chunk.changesMade());
  }

  @Test
  public void legacyChunkWithBlocksIsKept() {
    byte[] blocks = new byte[4096];
    blocks[10] = 1; // stone
    Chunk chunk = new Chunk(legacyChunk(DV_LEGACY, numericSection(blocks)));
    assertTrue(chunk.hasContent(null));
  }

  @Test
  public void legacyTileEntitiesAreKept() {
    CompoundTag data = legacyChunk(DV_LEGACY, numericSection(new byte[4096]));
    ListTag<CompoundTag> tileEntities = new ListTag<>(CompoundTag.class);
    tileEntities.add(named("Chest"));
    level(data).put("TileEntities", tileEntities);
    assertTrue(new Chunk(data).hasContent(null));
  }

  @Test
  public void legacySpecialBiomeIsKept() {
    CompoundTag data = legacyChunk(DV_LEGACY, numericSection(new byte[4096]));
    level(data).putByteArray("Biomes", new byte[]{1, 1, (byte) 2, 1}); // 2 = desert
    assertTrue(new Chunk(data).hasContent(null));
  }

  // ---------------------------------------------------------------------------------------------
  // Safety: unrecognised structures (e.g. POI) are never pruned
  // ---------------------------------------------------------------------------------------------

  @Test
  public void unknownChunkStructureIsKept() {
    CompoundTag data = new CompoundTag();
    data.putInt("DataVersion", DV_1_21);
    data.put("Sections", new ListTag<>(CompoundTag.class)); // POI-style, but no block sections we understand
    // No "sections" (modern key) -> not a recognised modern block chunk -> keep.
    assertTrue(new Chunk(data).hasContent(null));
  }

  // ---------------------------------------------------------------------------------------------
  // Builders
  // ---------------------------------------------------------------------------------------------

  private static CompoundTag named(String id) {
    CompoundTag tag = new CompoundTag();
    tag.put("id", new StringTag(id));
    return tag;
  }

  private static CompoundTag blockState(String name) {
    CompoundTag tag = new CompoundTag();
    tag.put("Name", new StringTag(name));
    return tag;
  }

  /** A 1.18+ section whose block palette contains the given block names. */
  private static CompoundTag modernSection(int y, String... blockNames) {
    CompoundTag section = new CompoundTag();
    section.putByte("Y", (byte) y);
    ListTag<CompoundTag> palette = new ListTag<>(CompoundTag.class);
    for (String name : blockNames) {
      palette.add(blockState(name));
    }
    CompoundTag blockStates = new CompoundTag();
    blockStates.put("palette", palette);
    section.put("block_states", blockStates);
    setBiome(section, "minecraft:plains");
    return section;
  }

  private static void setBiome(CompoundTag modernSection, String biome) {
    ListTag<StringTag> palette = new ListTag<>(StringTag.class);
    palette.add(new StringTag(biome));
    CompoundTag biomes = new CompoundTag();
    biomes.put("palette", palette);
    modernSection.put("biomes", biomes);
  }

  private static CompoundTag modernChunk(CompoundTag... sections) {
    CompoundTag data = new CompoundTag();
    data.putInt("DataVersion", DV_1_21);
    ListTag<CompoundTag> sectionList = new ListTag<>(CompoundTag.class);
    for (CompoundTag section : sections) {
      sectionList.add(section);
    }
    data.put("sections", sectionList);
    return data;
  }

  /** A 1.13-1.17 section with a direct {@code Palette} list. */
  private static CompoundTag paletteSection(String... blockNames) {
    CompoundTag section = new CompoundTag();
    ListTag<CompoundTag> palette = new ListTag<>(CompoundTag.class);
    for (String name : blockNames) {
      palette.add(blockState(name));
    }
    section.put("Palette", palette);
    return section;
  }

  /** A pre-1.13 section with a numeric {@code Blocks} array. */
  private static CompoundTag numericSection(byte[] blocks) {
    CompoundTag section = new CompoundTag();
    section.putByte("Y", (byte) 0);
    section.putByteArray("Blocks", blocks);
    return section;
  }

  /** A pre-1.18 chunk (Level-wrapped) holding the given sections. */
  private static CompoundTag legacyChunk(int dataVersion, CompoundTag... sections) {
    CompoundTag data = new CompoundTag();
    if (dataVersion > 0) {
      data.putInt("DataVersion", dataVersion);
    }
    CompoundTag level = new CompoundTag();
    ListTag<CompoundTag> sectionList = new ListTag<>(CompoundTag.class);
    for (CompoundTag section : sections) {
      sectionList.add(section);
    }
    level.put("Sections", sectionList);
    data.put("Level", level);
    return data;
  }

  private static CompoundTag entityChunk(int dataVersion, CompoundTag... entities) {
    CompoundTag data = new CompoundTag();
    data.putInt("DataVersion", dataVersion);
    data.putIntArray("Position", new int[]{0, 0});
    ListTag<CompoundTag> entityList = new ListTag<>(CompoundTag.class);
    for (CompoundTag entity : entities) {
      entityList.add(entity);
    }
    data.put("Entities", entityList);
    return data;
  }

  private static void addInlineEntities(CompoundTag levelWrappedChunk, CompoundTag... entities) {
    ListTag<CompoundTag> entityList = new ListTag<>(CompoundTag.class);
    for (CompoundTag entity : entities) {
      entityList.add(entity);
    }
    level(levelWrappedChunk).put("Entities", entityList);
  }

  private static CompoundTag level(CompoundTag data) {
    return data.getCompoundTag("Level");
  }
}
