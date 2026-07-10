/* Copyright (C) 2026, Carsten Hammer and contributors. SPDX-License-Identifier: BSD-3-Clause */
package io.github.carstenartur.jgit.storage.hibernate.architecture;

/** Language-neutral parser SPI for versioned architecture DSLs. */
public interface ArchitectureDslParser {
  String dslId();
  boolean supports(String path, String content);
  ArchitectureDslParseResult parse(ArchitectureDslSource source);
}
