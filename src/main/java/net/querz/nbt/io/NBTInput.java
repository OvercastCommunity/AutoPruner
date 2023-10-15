package net.querz.nbt.io;

import java.io.IOException;

public interface NBTInput {

  NamedTag readTag(int maxDepth) throws IOException;

}
