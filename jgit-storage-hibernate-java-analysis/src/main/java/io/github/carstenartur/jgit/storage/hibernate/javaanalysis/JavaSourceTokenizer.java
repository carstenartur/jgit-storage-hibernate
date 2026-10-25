/* Copyright (C) 2026, Carsten Hammer and contributors. SPDX-License-Identifier: BSD-3-Clause */
package io.github.carstenartur.jgit.storage.hibernate.javaanalysis;

import static org.eclipse.jdt.core.compiler.ITerminalSymbols.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.ToolFactory;
import org.eclipse.jdt.core.compiler.IScanner;
import org.eclipse.jdt.core.compiler.InvalidInputException;

/** Public JDT scanner with raw literal spelling and same-position identifier parts. */
final class JavaSourceTokenizer extends Tokenizer {
  private static final Pattern DOUBLE_QUOTE = Pattern.compile("(?:\"|\\\\u+0022)");
  private static final Pattern SINGLE_QUOTE = Pattern.compile("(?:'|\\\\u+0027)");
  private static final Pattern DOUBLE_QUOTE_END = Pattern.compile(DOUBLE_QUOTE.pattern() + "$");
  private static final Pattern SINGLE_QUOTE_END = Pattern.compile(SINGLE_QUOTE.pattern() + "$");
  private static final Pattern TEXT_BLOCK_END = Pattern.compile("(?:" + DOUBLE_QUOTE.pattern() + "){3}$");

  private final CharTermAttribute term = addAttribute(CharTermAttribute.class);
  private final OffsetAttribute offset = addAttribute(OffsetAttribute.class);
  private final PositionIncrementAttribute increment = addAttribute(PositionIncrementAttribute.class);
  private IScanner scanner;
  private int sourceLength;
  private int cursor;
  private boolean exhausted;
  private List<String> parts = List.of();
  private int nextPart;
  private int tokenStart;
  private int tokenEnd;

  @Override
  public void reset() throws IOException {
    super.reset();
    var source = new StringBuilder();
    char[] buffer = new char[4096];
    for (int count; (count = input.read(buffer)) != -1;) source.append(buffer, 0, count);
    sourceLength = source.length();
    scanner = ToolFactory.createScanner(true, false, false, JavaCore.VERSION_25, JavaCore.VERSION_25, false);
    scanner.setSource(source.toString().toCharArray());
    cursor = 0;
    exhausted = false;
    parts = List.of();
    nextPart = 0;
  }

  @Override
  public boolean incrementToken() throws IOException {
    clearAttributes();
    if (nextPart < parts.size()) {
      emit(parts.get(nextPart++), 0);
      return true;
    }
    parts = List.of();
    nextPart = 0;
    if (scanner == null || exhausted) return false;
    while (true) {
      int kind;
      try {
        kind = scanner.getNextToken();
      } catch (InvalidInputException invalid) {
        int next = scanner.getCurrentTokenEndPosition() + 1;
        // Recovery never retries an unchanged position, including malformed Unicode at EOF.
        if (next <= cursor) {
          next = cursor + 1;
          if (next < sourceLength) scanner.resetTo(next, sourceLength - 1);
        }
        cursor = Math.min(next, sourceLength);
        if (cursor >= sourceLength) {
          exhausted = true;
          return false;
        }
        continue;
      }
      if (kind == TokenNameEOF) {
        exhausted = true;
        return false;
      }
      tokenStart = scanner.getCurrentTokenStartPosition();
      tokenEnd = Math.min(sourceLength, scanner.getCurrentTokenEndPosition() + 1);
      cursor = Math.max(cursor, tokenEnd);
      if (!searchable(kind)) continue;
      String text = new String(scanner.getRawTokenSource());
      if (kind == TokenNameStringLiteral || kind == TokenNameTextBlock) {
        text = literalBody(text, DOUBLE_QUOTE, kind == TokenNameTextBlock ? 3 : 1);
      } else if (kind == TokenNameCharacterLiteral || kind == TokenNameSingleQuoteStringLiteral) {
        text = literalBody(text, SINGLE_QUOTE, 1);
      }
      if (text.isEmpty()) continue;
      if (kind == TokenNameIdentifier) parts = identifierParts(text);
      emit(text, 1);
      return true;
    }
  }

  private void emit(String text, int positionIncrement) {
    term.setEmpty().append(text);
    increment.setPositionIncrement(positionIncrement);
    offset.setOffset(correctOffset(tokenStart), correctOffset(tokenEnd));
  }

  private static String literalBody(String raw, Pattern quote, int count) {
    var prefix = quote.matcher(raw);
    int start = 0;
    for (int i = 0; i < count; i++) {
      prefix.region(start, raw.length());
      if (!prefix.lookingAt()) throw new IllegalArgumentException("Unsupported Java literal delimiter");
      start = prefix.end();
    }
    var suffix = (count == 3 ? TEXT_BLOCK_END : quote == DOUBLE_QUOTE ? DOUBLE_QUOTE_END : SINGLE_QUOTE_END).matcher(raw);
    if (!suffix.find(start)) throw new IllegalArgumentException("Unsupported Java literal delimiter");
    return raw.substring(start, suffix.start());
  }

  private static List<String> identifierParts(String text) {
    var result = new ArrayList<String>();
    int start = 0;
    int previous = text.codePointAt(0);
    for (int i = Character.charCount(previous); i < text.length();) {
      int current = text.codePointAt(i);
      if (Character.isLowerCase(previous) && Character.isUpperCase(current)) {
        result.add(text.substring(start, i));
        start = i;
      }
      previous = current;
      i += Character.charCount(current);
    }
    if (start == 0) return List.of();
    result.add(text.substring(start));
    return result;
  }

  private static boolean searchable(int kind) {
    return switch (kind) {
      case TokenNameIdentifier, TokenNameIntegerLiteral, TokenNameLongLiteral,
          TokenNameFloatingPointLiteral, TokenNameDoubleLiteral, TokenNameCharacterLiteral,
          TokenNameStringLiteral, TokenNameTextBlock, TokenNameSingleQuoteStringLiteral,
          TokenNameabstract, TokenNameassert, TokenNameboolean, TokenNamebreak, TokenNamebyte,
          TokenNamecase, TokenNamecatch, TokenNamechar, TokenNameclass, TokenNamecontinue,
          TokenNamedefault, TokenNamedo, TokenNamedouble, TokenNameelse, TokenNameextends,
          TokenNamefalse, TokenNamefinal, TokenNamefinally, TokenNamefloat, TokenNamefor,
          TokenNameif, TokenNameimplements, TokenNameimport, TokenNameinstanceof, TokenNameint,
          TokenNameinterface, TokenNamelong, TokenNamenative, TokenNamenew, TokenNamenull,
          TokenNamepackage, TokenNameprivate, TokenNameprotected, TokenNamepublic, TokenNamereturn,
          TokenNameshort, TokenNamestatic, TokenNamestrictfp, TokenNamesuper, TokenNameswitch,
          TokenNamesynchronized, TokenNamethis, TokenNamethrow, TokenNamethrows, TokenNametransient,
          TokenNametrue, TokenNametry, TokenNamevoid, TokenNamevolatile, TokenNamewhile,
          TokenNameenum, TokenNameconst, TokenNamegoto, TokenNameRestrictedIdentifierYield,
          TokenNameRestrictedIdentifierpermits, TokenNameRestrictedIdentifierrecord,
          TokenNameRestrictedIdentifiersealed, TokenNamenon_sealed, TokenNameRestrictedIdentifierWhen,
          TokenNameUNDERSCORE -> true;
      default -> false;
    };
  }

  @Override
  public void end() throws IOException {
    super.end();
    int end = correctOffset(sourceLength);
    offset.setOffset(end, end);
  }

  @Override
  public void close() throws IOException {
    try {
      super.close();
    } finally {
      scanner = null;
      parts = List.of();
      sourceLength = 0;
    }
  }
}
