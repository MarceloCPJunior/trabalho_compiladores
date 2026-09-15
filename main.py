from __future__ import annotations

import sys
from dataclasses import dataclass
from pathlib import Path


KEYWORDS = {"rem", "input", "let", "print", "goto", "if", "end"}
RELATIONAL_OPERATORS = {">", ">=", "<", "<=", "==", "!="}


class AnalysisError(Exception):
    def __init__(self, line_number: int, message: str) -> None:
        super().__init__(f"Line {line_number}: {message}")
        self.line_number = line_number
        self.message = message


@dataclass
class SourceLine:
    line_number: int
    text: str


@dataclass
class Token:
    kind: str
    value: str
    line_number: int


class TokenStream:
    def __init__(self, tokens: list[Token], line_number: int) -> None:
        self.tokens = tokens
        self.line_number = line_number
        self.position = 0

    def peek(self) -> Token | None:
        if self.position >= len(self.tokens):
            return None
        return self.tokens[self.position]

    def advance(self) -> Token:
        token = self.peek()
        if token is None:
            raise AnalysisError(self.line_number, "unexpected end of statement")
        self.position += 1
        return token

    def expect(self, kind: str, value: str | None = None) -> Token:
        token = self.advance()
        if token.kind != kind or (value is not None and token.value != value):
            expected = value if value is not None else kind.lower()
            raise AnalysisError(
                self.line_number,
                f"expected {expected}, found '{token.value}'",
            )
        return token

    def ensure_finished(self) -> None:
        token = self.peek()
        if token is not None:
            raise AnalysisError(
                self.line_number,
                f"unexpected token '{token.value}' at end of statement",
            )


def parse_source(source: str) -> list[SourceLine]:
    lines: list[SourceLine] = []
    previous_line_number = None

    for physical_line, raw_line in enumerate(source.splitlines(), start=1):
        if not raw_line.strip():
            continue

        parts = raw_line.lstrip().split(maxsplit=1)
        if not parts or not parts[0].isdigit():
            raise AnalysisError(physical_line, "statement must start with a numeric line number")

        line_number = int(parts[0])
        if previous_line_number is not None and line_number <= previous_line_number:
            raise AnalysisError(
                line_number,
                f"line numbers must be strictly increasing (previous was {previous_line_number})",
            )

        statement = parts[1] if len(parts) > 1 else ""
        if not statement.strip():
            raise AnalysisError(line_number, "missing command after line number")

        previous_line_number = line_number
        lines.append(SourceLine(line_number, statement.rstrip()))

    if not lines:
        raise AnalysisError(0, "source file is empty")

    return lines


def tokenize_statement(source_line: SourceLine) -> list[Token]:
    text = source_line.text.lstrip()
    line_number = source_line.line_number

    first_word_end = 0
    while first_word_end < len(text) and text[first_word_end].isalpha():
        if text[first_word_end].isupper():
            raise AnalysisError(line_number, "uppercase letters are only allowed inside rem comments")
        first_word_end += 1

    command = text[:first_word_end]
    if not command:
        raise AnalysisError(line_number, "missing command")
    if command not in KEYWORDS:
        raise AnalysisError(line_number, f"unknown command '{command}'")
    if command == "rem":
        return [Token("KEYWORD", "rem", line_number)]

    tokens: list[Token] = [Token("KEYWORD", command, line_number)]
    index = first_word_end

    while index < len(text):
        char = text[index]
        if char.isspace():
            index += 1
            continue
        if char.isupper():
            raise AnalysisError(line_number, "uppercase letters are only allowed inside rem comments")
        if char.isdigit():
            start = index
            while index < len(text) and text[index].isdigit():
                index += 1
            tokens.append(Token("NUMBER", text[start:index], line_number))
            continue
        if char.islower():
            start = index
            while index < len(text) and text[index].islower():
                index += 1
            word = text[start:index]
            if word in KEYWORDS:
                tokens.append(Token("KEYWORD", word, line_number))
            elif len(word) == 1:
                tokens.append(Token("IDENT", word, line_number))
            else:
                raise AnalysisError(
                    line_number,
                    f"invalid identifier '{word}' (variables must be a single lowercase letter)",
                )
            continue
        if char in "()":
            raise AnalysisError(line_number, "parentheses are not allowed in SIMPLE expressions")
        if char in "+-*/%":
            tokens.append(Token("ARITH", char, line_number))
            index += 1
            continue
        if char == "=":
            if index + 1 < len(text) and text[index + 1] == "=":
                tokens.append(Token("RELOP", "==", line_number))
                index += 2
            else:
                tokens.append(Token("ASSIGN", "=", line_number))
                index += 1
            continue
        if char in "<>!":
            if index + 1 < len(text) and text[index + 1] == "=":
                operator = text[index : index + 2]
                if operator in RELATIONAL_OPERATORS:
                    tokens.append(Token("RELOP", operator, line_number))
                    index += 2
                    continue
            if char in {"<", ">"}:
                tokens.append(Token("RELOP", char, line_number))
                index += 1
                continue
            raise AnalysisError(line_number, f"invalid token starting at '{text[index:]}'")
        raise AnalysisError(line_number, f"invalid character '{char}'")

    return tokens


class Analyzer:
    def __init__(self) -> None:
        self.variables: set[str] = set()
        self.goto_targets: list[tuple[int, int]] = []
        self.line_numbers: set[int] = set()

    def analyze(self, source: str) -> str:
        program = parse_source(source)
        self.line_numbers = {source_line.line_number for source_line in program}

        for source_line in program:
            tokens = tokenize_statement(source_line)
            self.parse_statement(TokenStream(tokens, source_line.line_number))

        self.validate_gotos()

        variables = ", ".join(sorted(self.variables)) or "(none)"
        return (
            "Analysis completed successfully.\n"
            f"Statements: {len(program)}\n"
            f"Variables: {variables}"
        )

    def parse_statement(self, stream: TokenStream) -> None:
        command = stream.expect("KEYWORD").value

        if command == "rem":
            stream.ensure_finished()
            return
        if command == "input":
            variable = stream.expect("IDENT").value
            self.variables.add(variable)
            stream.ensure_finished()
            return
        if command == "let":
            variable = stream.expect("IDENT").value
            self.variables.add(variable)
            stream.expect("ASSIGN", "=")
            self.parse_expression(stream)
            stream.ensure_finished()
            return
        if command == "print":
            variable = stream.expect("IDENT").value
            self.variables.add(variable)
            stream.ensure_finished()
            return
        if command == "goto":
            target = int(stream.expect("NUMBER").value)
            self.goto_targets.append((stream.line_number, target))
            stream.ensure_finished()
            return
        if command == "if":
            self.parse_expression(stream)
            relational = stream.expect("RELOP")
            if relational.value not in RELATIONAL_OPERATORS:
                raise AnalysisError(stream.line_number, f"invalid relational operator '{relational.value}'")
            self.parse_expression(stream)
            stream.expect("KEYWORD", "goto")
            target = int(stream.expect("NUMBER").value)
            self.goto_targets.append((stream.line_number, target))
            stream.ensure_finished()
            return
        if command == "end":
            stream.ensure_finished()
            return

        raise AnalysisError(stream.line_number, f"unsupported command '{command}'")

    def parse_expression(self, stream: TokenStream) -> None:
        self.parse_term(stream)
        while True:
            token = stream.peek()
            if token is None or token.kind != "ARITH" or token.value not in {"+", "-"}:
                return
            stream.advance()
            self.parse_term(stream)

    def parse_term(self, stream: TokenStream) -> None:
        self.parse_factor(stream)
        while True:
            token = stream.peek()
            if token is None or token.kind != "ARITH" or token.value not in {"*", "/", "%"}:
                return
            stream.advance()
            self.parse_factor(stream)

    def parse_factor(self, stream: TokenStream) -> None:
        token = stream.peek()
        if token is None:
            raise AnalysisError(stream.line_number, "expected expression")
        if token.kind == "NUMBER":
            stream.advance()
            return
        if token.kind == "IDENT":
            self.variables.add(stream.advance().value)
            return
        raise AnalysisError(stream.line_number, f"expected number or variable, found '{token.value}'")

    def validate_gotos(self) -> None:
        for line_number, target in self.goto_targets:
            if target not in self.line_numbers:
                raise AnalysisError(line_number, f"goto target {target} does not exist")


def read_source_from_argv() -> str:
    if len(sys.argv) > 2:
        raise SystemExit("Usage: python3 main.py [source.simple]")
    if len(sys.argv) == 2:
        return Path(sys.argv[1]).read_text(encoding="utf-8")
    return sys.stdin.read()


def main() -> int:
    try:
        source = read_source_from_argv()
        analyzer = Analyzer()
        print(analyzer.analyze(source))
        return 0
    except FileNotFoundError as error:
        print(f"Error: could not open '{error.filename}'", file=sys.stderr)
        return 1
    except AnalysisError as error:
        print(f"Error: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
