package com.pmart.customerassistant.domain;

import java.util.List;

/** 시험용 청크 생성. */
public final class Chunks {

  private Chunks() {}

  public static RetrievedChunk chunk(
      String id, Authority authority, double similarity, String content) {
    return chunk(
        id, "docs/" + id + ".md", authority, Lifecycle.ACTIVE, List.of(), 3, similarity, content);
  }

  public static RetrievedChunk chunk(
      String id,
      String documentId,
      Authority authority,
      Lifecycle lifecycle,
      List<String> proposed,
      int precedence,
      double similarity,
      String content) {
    return new RetrievedChunk(
        id,
        documentId,
        documentId,
        "절 " + id,
        authority,
        lifecycle,
        null,
        proposed,
        precedence,
        null,
        null,
        null,
        null,
        "operator",
        content,
        similarity,
        similarity);
  }
}
