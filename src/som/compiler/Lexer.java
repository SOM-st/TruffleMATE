/**
 * Copyright (c) 2009 Michael Haupt, michael.haupt@hpi.uni-potsdam.de
 * Software Architecture Group, Hasso Plattner Institute, Potsdam, Germany
 * http://www.hpi.uni-potsdam.de/swa/
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package som.compiler;

public final class Lexer {

  private static final String SEPARATOR = "----";
  private static final String PRIMITIVE = "primitive";

  public static class Peek {
    public Peek(final Symbol sym, final String text) {
      nextSym = sym;
      nextText = text;
    }

    public final Symbol nextSym;
    public final String nextText;
  }

  private class LexerState {
    LexerState() { }
    LexerState(final LexerState old) {
      lineNumber = old.lineNumber;
      lastLineEnd  = old.lastLineEnd;
      lastNonWhiteCharIdx = old.lastNonWhiteCharIdx;
      ptr        = old.ptr;
      sym        = old.sym;
      symc       = old.symc;
      text = new StringBuilder(old.text);
      startCoord = old.startCoord;
    }

    public void set(final Symbol sym, final char symChar, final String text) {
      this.sym  = sym;
      this.symc = symChar;
      this.text = new StringBuilder(text);
    }

    public void set(final Symbol sym) {
      this.sym = sym;
      this.symc = 0;
      this.text = new StringBuilder();
    }

    private int                 lineNumber;

    /** All characters read, excluding the current line, incl. line break. */
    private int                 lastLineEnd;

    private int lastNonWhiteCharIdx;

    private int                 ptr;

    private Symbol              sym;
    private char                symc;
    private StringBuilder       text;

    private SourceCoordinate    startCoord;

    int incPtr() {
      return incPtr(1);
    }

    int incPtr(final int val) {
      int cur = ptr;
      ptr += val;
      lastNonWhiteCharIdx = ptr;
      return cur;
    }
  }

  private final String content;

  private boolean             peekDone;
  private LexerState          state;
  private LexerState          stateAfterPeek;

  protected Lexer(final String content) {
    this.content = content;
    peekDone = false;
    state = new LexerState();
    state.ptr = 0;
    state.text = new StringBuilder();
    state.lineNumber = 1;
    state.lastLineEnd = 0;
    state.lastNonWhiteCharIdx = 0;
  }

  public static final class SourceCoordinate {
    public final int startLine;
    public final int startColumn;
    public final int charIndex;
    public final int charLength;

    public SourceCoordinate(final int startLine, final int startColumn,
        final int charIndex, final int length) {
      this.startLine   = startLine;
      this.startColumn = startColumn;
      this.charIndex   = charIndex;
      this.charLength = length;
      assert startLine   >= 0;
      assert startColumn >= 0;
      assert charIndex   >= 0;
    }

    @Override
    public String toString() {
      return "SrcCoord(line: " + startLine + ", col: " + startColumn + ")";
    }
  }

  public String getCurrentLine() {
    int endLine = content.indexOf("\n", state.lastLineEnd + 1);
    return content.substring(state.lastLineEnd + 1, endLine);
  }

  public SourceCoordinate getStartCoordinate() {
    return state.startCoord;
  }

  protected Symbol getSym() {
    if (peekDone) {
      peekDone = false;
      state = stateAfterPeek;
      stateAfterPeek = null;
      state.text = new StringBuilder(state.text);
      return state.sym;
    }

    if (endOfContent()) {
      state.set(Symbol.NONE);
      return state.sym;
    }

    do {
      skipWhiteSpace();
      skipComment();
    } while (Character.isWhitespace(currentChar()) || currentChar() == '"');


    state.startCoord = new SourceCoordinate(state.lineNumber, state.ptr - state.lastLineEnd, state.ptr, state.lastNonWhiteCharIdx);

    if (currentChar() == '\'') {
      lexString();
    } else if (currentChar() == '$') {
      lexCharacter();
    } else if (currentChar() == '[') {
      match(Symbol.NewBlock);
    } else if (currentChar() == ']') {
      match(Symbol.EndBlock);
    } else if (currentChar() == ':') {
      if (nextChar() == '=') {
        state.incPtr(2);
        state.set(Symbol.Assign, '\0', ":=");
      } else {
        match(Symbol.Colon);
      }
    } else if (currentChar() == '(') {
      match(Symbol.NewTerm);
    } else if (currentChar() == ')') {
      match(Symbol.EndTerm);
    } else if (currentChar() == '#') {
      match(Symbol.Pound);
    } else if (currentChar() == '^') {
      match(Symbol.Exit);
    } else if (currentChar() == '.') {
      match(Symbol.Period);
    } else if (currentChar() == ';') {
      match(Symbol.SemiColon);
    } else if (currentChar() == '-') {
      if (content.startsWith(SEPARATOR, state.ptr)) {
        state.text = new StringBuilder();
        while (currentChar() == '-') {
          state.text.append(bufchar(state.incPtr()));
        }
        state.sym = Symbol.Separator;
      } else {
        lexOperator();
      }
    } else if (isOperator(currentChar())) {
      lexOperator();
    } else if (nextWordInBufferIs(PRIMITIVE)) {
      state.incPtr(PRIMITIVE.length());
      state.set(Symbol.Primitive, '\0', PRIMITIVE);
    } else if (Character.isLetter(currentChar())) {
      state.set(Symbol.Identifier);
      while (isIdentifierChar(currentChar())) {
        state.text.append(bufchar(state.incPtr()));
      }
      if (bufchar(state.ptr) == ':') {
        state.sym = Symbol.Keyword;
        state.incPtr();
        state.text.append(':');
        if (Character.isLetter(currentChar())) {
          state.sym = Symbol.KeywordSequence;
          while (Character.isLetter(currentChar()) || currentChar() == ':') {
            state.text.append(bufchar(state.incPtr()));
          }
        }
      }
    } else if (Character.isDigit(currentChar())) {
      lexNumber();
    } else {
      state.set(Symbol.NONE, currentChar(), "" + currentChar());
    }

    return state.sym;
  }

  private void lexNumber() {
    state.set(Symbol.Integer);

    boolean sawDecimalMark = false;

    do {
      state.text.append(bufchar(state.incPtr()));

      if (!sawDecimalMark      &&
          '.' == currentChar() &&
          Character.isDigit(bufchar(state.ptr + 1))) {
        state.sym = Symbol.Double;
        state.text.append(bufchar(state.incPtr()));
      }
    } while (Character.isDigit(currentChar()));
  }

  private void lexEscapeChar() {
    assert !endOfContent();

    char current = currentChar();
    switch (current) {
      case 't': state.text.append("\t"); break;
      case 'b': state.text.append("\b"); break;
      case 'n': state.text.append("\n"); break;
      case 'r': state.text.append("\r"); break;
      case 'f': state.text.append("\f"); break;
      case '\'': state.text.append("'"); break;
      case '\\': state.text.append("\\"); break;
    }
    state.incPtr();
  }

  private void lexStringChar() {
    char cur = currentChar();
    if (cur == '\\') {
      state.incPtr();
      lexEscapeChar();
    } else {
      state.text.append(cur);
      state.incPtr();
    }

    if (cur == '\n') {
      state.lineNumber += 1;
      state.lastLineEnd = state.ptr - 1;
    }
  }

  private void lexString() {
    state.set(Symbol.STString);
    state.incPtr();

    while (currentChar() != '\'') {
      lexStringChar();
    }

    state.incPtr();
  }

  private void lexCharacter() {
    state.set(Symbol.STChar);
    state.incPtr();
    char c = currentChar();
    if (c == '"' && nextChar() == '"') {
      state.text.append('"');
      state.incPtr(2);
    } else {
      acceptChar();
    }

    if (currentChar() == '"') {
      state.incPtr();
    }
  }


  private void lexOperator() {
    if (isOperator(nextChar())) {
      state.set(Symbol.OperatorSequence);
      while (isOperator(currentChar())) {
        state.text.append(bufchar(state.incPtr()));
      }
    } else if (currentChar() == '~') {
      match(Symbol.Not);
    } else if (currentChar() == '&') {
      match(Symbol.And);
    } else if (currentChar() == '|') {
      match(Symbol.Or);
    } else if (currentChar() == '*') {
      match(Symbol.Star);
    } else if (currentChar() == '/') {
      match(Symbol.Div);
    } else if (currentChar() == '\\') {
      match(Symbol.Mod);
    } else if (currentChar() == '+') {
      match(Symbol.Plus);
    } else if (currentChar() == '=') {
      match(Symbol.Equal);
    } else if (currentChar() == '>') {
      match(Symbol.More);
    } else if (currentChar() == '<') {
      match(Symbol.Less);
    } else if (currentChar() == ',') {
      match(Symbol.Comma);
    } else if (currentChar() == '@') {
      match(Symbol.At);
    } else if (currentChar() == '%') {
      match(Symbol.Per);
    } else if (currentChar() == '-') {
      match(Symbol.Minus);
    }
  }

  protected Symbol peek() {
    LexerState old = new LexerState(state);
    if (peekDone) {
      throw new IllegalStateException("SOM lexer: cannot peek twice!");
    }
    getSym();
    Symbol nextSym = state.sym;
    stateAfterPeek = state;
    state = old;

    peekDone = true;
    return nextSym;
  }

  protected String getText() {
    return state.text.toString();
  }

  protected int getCurrentLineNumber() {
    return state.lineNumber;
  }

  protected int getCurrentColumn() {
    return state.ptr + 1 - state.lastLineEnd;
  }

  // All characters read and processed, including current line
  protected int getNumberOfCharactersRead() {
    return state.startCoord.charIndex;
  }

  private void skipWhiteSpace() {
    char curr;
    while (!endOfContent() && Character.isWhitespace(curr = currentChar())) {
      if (curr == '\n') {
        state.lineNumber += 1;
        state.lastLineEnd = state.ptr;
      }
      state.ptr++;
    }
  }

  private void skipComment() {
    if (currentChar() == '"') {
      state.incPtr();
      while (!endOfContent() && currentChar() != '"') {
        if (currentChar() == '\n') {
          state.lineNumber += 1;
          state.lastLineEnd = state.ptr;
        }
        state.incPtr();
      }
      if (currentChar() == '"') {
        state.incPtr();
      }
    }
  }

  protected char nextChar() {
    return bufchar(state.ptr + 1);
  }

  protected char nextChar(final int offset) {
    return bufchar(state.ptr + offset);
  }

  char currentChar() {
    return bufchar(state.ptr);
  }

  protected char acceptChar() {
    char c = bufchar(state.incPtr());
    state.text.append(c);

    if (Character.isHighSurrogate(c)) {
      c = bufchar(state.incPtr());
      state.text.append(c);
      assert !Character.isHighSurrogate(c);
    }
    return c;
  }

  private boolean endOfContent() {
    return state.ptr >= content.length();
  }

  private boolean isOperator(final char c) {
    return c == '~' || c == '&' || c == '|' || c == '*' || c == '/'
        || c == '\\' || c == '+' || c == '=' || c == '>' || c == '<'
        || c == ',' || c == '@' || c == '%' || c == '-';
  }

  protected static boolean isDigit(final char c) {
    return c >= '0' && c <= '9';
  }

  protected static boolean isUppercaseLetter(final char c) {
    return c >= 'A' && c <= 'Z';
  }

  private void match(final Symbol s) {
    state.set(s, currentChar(), "" + currentChar());
    state.incPtr();
  }

  private char bufchar(final int p) {
    return p >= content.length() ? '\0' : content.charAt(p);
  }

  private boolean isIdentifierChar(final char c) {
    return Character.isLetterOrDigit(c) || c == '_';
  }

  private boolean nextWordInBufferIs(final String text) {
    if (!content.startsWith(text, state.ptr)) {
      return false;
    }
    char next = nextChar(text.length());
    return !(isIdentifierChar(next) || next == ':');
  }
}
