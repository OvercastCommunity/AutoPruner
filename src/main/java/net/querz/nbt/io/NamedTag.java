package net.querz.nbt.io;

import net.querz.nbt.tag.Tag;

public class NamedTag {

  private final String name;
  private Tag<?> tag;

  public NamedTag(String name, Tag<?> tag) {
    this.name = name;
    this.tag = tag;
  }

  public String getName() {
    return name;
  }

  public Tag<?> getTag() {
    return tag;
  }

  public void setTag(Tag<?> tag) {
    this.tag = tag;
  }
}
