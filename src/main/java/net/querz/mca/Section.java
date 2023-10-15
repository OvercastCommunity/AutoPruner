package net.querz.mca;

import net.querz.nbt.tag.ByteArrayTag;
import net.querz.nbt.tag.CompoundTag;

public class Section implements Comparable<Section> {
  private final CompoundTag rootTag;
  private final byte height;
  private final byte[] blocks;
  private final byte[] add;
  private final byte[] data;
  private final byte[] blockLight;
  private final byte[] skyLight;

  public Section(CompoundTag sectionRoot) {
    rootTag = sectionRoot;
    height = sectionRoot.getNumber("Y").byteValue();

    ByteArrayTag blocks = sectionRoot.getByteArrayTag("Blocks");
    ByteArrayTag add = sectionRoot.getByteArrayTag("Add");
    ByteArrayTag data = sectionRoot.getByteArrayTag("Data");
    ByteArrayTag blockLight = sectionRoot.getByteArrayTag("BlockLight");
    ByteArrayTag skyLight = sectionRoot.getByteArrayTag("SkyLight");

    this.blocks = blocks != null ? blocks.getValue() : null;
    this.add = add != null ? add.getValue() : null;
    this.data = data != null ? data.getValue() : null;
    this.blockLight = blockLight != null ? blockLight.getValue() : null;
    this.skyLight = skyLight != null ? skyLight.getValue() : null;
  }

  @Override
  public int compareTo(Section o) {
    if (o == null) {
      return -1;
    }
    return Integer.compare(height, o.height);
  }

  /**
   * Checks whether the data of this Section is empty.
   *
   * @return true if empty
   */
  public boolean isEmpty() {
    if (blocks == null) {
      return true;
    }

    // https://stackoverflow.com/questions/23824364/fastest-way-to-check-if-a-byte-array-is-all-zeros
    for (byte b : blocks) {
      if (b != 0) {
        return false;
      }
    }
    if (add != null) {
      for (byte b : add) {
        if (b != 0) {
          return false;
        }
      }
    }

    return true;
  }

  /**
   * Updates the raw CompoundTag that this Section is based on.
   * This must be called before saving a Section to disk if the Section was manually created
   * to set the Y of this Section.
   *
   * @param y The Y-value of this Section
   * @return A reference to the raw CompoundTag this Section is based on
   */
  public CompoundTag updateHandle(int y) {
    rootTag.putByte("Y", (byte) y);
    if (blocks != null) {
      rootTag.putByteArray("Blocks", blocks);
    }
    if (add != null) {
      rootTag.putByteArray("Add", add);
    }
    if (data != null) {
      rootTag.putByteArray("Data", data);
    }
    if (blockLight != null) {
      rootTag.putByteArray("BlockLight", blockLight);
    }
    if (skyLight != null) {
      rootTag.putByteArray("SkyLight", skyLight);
    }
    return rootTag;
  }

  public CompoundTag updateHandle() {
    return updateHandle(height);
  }
}
