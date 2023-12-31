package net.querz.nbt.io;

import net.querz.io.Serializer;
import net.querz.nbt.tag.Tag;

import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.GZIPOutputStream;

public class NBTSerializer implements Serializer<NamedTag> {

  private final boolean compressed;

  public NBTSerializer(boolean compressed) {
    this.compressed = compressed;
  }

  @Override
  public void toStream(NamedTag object, OutputStream out) throws IOException {
    NBTOutput nbtOut;
    OutputStream output;
    if (compressed) {
      output = new GZIPOutputStream(out, true);
    } else {
      output = out;
    }

    nbtOut = new NBTOutputStream(output);
    nbtOut.writeTag(object, Tag.DEFAULT_MAX_DEPTH);
    nbtOut.flush();
  }
}
