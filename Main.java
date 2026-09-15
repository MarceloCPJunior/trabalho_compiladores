import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

public class Main {
    private static final Set<String> KEYWORDS = Set.of("rem", "input", "let", "print", "goto", "if", "end");
    private static final Set<String> RELATIONAL_OPERATORS = Set.of(">", ">=", "<", "<=", "==", "!=");

    private enum TokenKind {
        KEYWORD,
        IDENT,
        NUMBER,
        ASSIGN,
        ARITH,
        RELOP
    }

    private static final class AnalysisException extends Exception {
        AnalysisException(int lineNumber, String message) {
            super("Line " + lineNumber + ": " + message);
        }
    }

    private static final class SourceLine {
        private final int lineNumber;
        private final String text;

        SourceLine(int lineNumber, String text) {
            this.lineNumber = lineNumber;
            this.text = text;
        }
    }

    private static final class Token {
        private final TokenKind kind;
        private final String value;

        Token(TokenKind kind, String value) {
            this.kind = kind;
            this.value = value;
        }
    }

    private static final class TokenStream {
        private final List<Token> tokens;
        private final int lineNumber;
        private int position;

        TokenStream(List<Token> tokens, int lineNumber) {
            this.tokens = tokens;
            this.lineNumber = lineNumber;
            this.position = 0;
        }

        Token peek() {
            if (position >= tokens.size()) {
                return null;
            }
            return tokens.get(position);
        }

        Token advance() throws AnalysisException {
            Token token = peek();
            if (token == null) {
                throw new AnalysisException(lineNumber, "unexpected end of statement");
            }
            position++;
            return token;
        }

        Token expect(TokenKind kind, String value) throws AnalysisException {
            Token token = advance();
            if (token.kind != kind || (value != null && !value.equals(token.value))) {
                String expected = value != null ? value : kind.name().toLowerCase();
                throw new AnalysisException(lineNumber, "expected " + expected + ", found '" + token.value + "'");
            }
            return token;
        }

        void ensureFinished() throws AnalysisException {
            Token token = peek();
            if (token != null) {
                throw new AnalysisException(lineNumber, "unexpected token '" + token.value + "' at end of statement");
            }
        }
    }

    private static final class Analyzer {
        private final TreeSet<String> variables = new TreeSet<>();
        private final List<GotoTarget> gotoTargets = new ArrayList<>();
        private final Set<Integer> lineNumbers = new HashSet<>();

        String analyze(String source) throws AnalysisException {
            variables.clear();
            gotoTargets.clear();
            lineNumbers.clear();

            List<SourceLine> program = parseSource(source);
            for (SourceLine sourceLine : program) {
                lineNumbers.add(sourceLine.lineNumber);
            }

            for (SourceLine sourceLine : program) {
                List<Token> tokens = tokenizeStatement(sourceLine);
                parseStatement(new TokenStream(tokens, sourceLine.lineNumber));
            }

            validateGotos();

            String variableList = variables.isEmpty() ? "(none)" : String.join(", ", variables);
            return "Analysis completed successfully.\n"
                + "Statements: " + program.size() + "\n"
                + "Variables: " + variableList;
        }

        private void parseStatement(TokenStream stream) throws AnalysisException {
            String command = stream.expect(TokenKind.KEYWORD, null).value;

            switch (command) {
                case "rem":
                    stream.ensureFinished();
                    return;
                case "input":
                    variables.add(stream.expect(TokenKind.IDENT, null).value);
                    stream.ensureFinished();
                    return;
                case "let":
                    variables.add(stream.expect(TokenKind.IDENT, null).value);
                    stream.expect(TokenKind.ASSIGN, "=");
                    parseExpression(stream);
                    stream.ensureFinished();
                    return;
                case "print":
                    variables.add(stream.expect(TokenKind.IDENT, null).value);
                    if (stream.peek() != null) {
                        throw new AnalysisException(stream.lineNumber, "print accepts only a single variable");
                    }
                    return;
                case "goto":
                    gotoTargets.add(new GotoTarget(stream.lineNumber, Integer.parseInt(stream.expect(TokenKind.NUMBER, null).value)));
                    stream.ensureFinished();
                    return;
                case "if":
                    parseExpression(stream);
                    if (stream.peek() != null && stream.peek().kind == TokenKind.ASSIGN) {
                        throw new AnalysisException(stream.lineNumber, "'=' is only valid for assignment; use '==' in conditions");
                    }
                    Token operator = stream.expect(TokenKind.RELOP, null);
                    if (!RELATIONAL_OPERATORS.contains(operator.value)) {
                        throw new AnalysisException(stream.lineNumber, "invalid relational operator '" + operator.value + "'");
                    }
                    parseExpression(stream);
                    stream.expect(TokenKind.KEYWORD, "goto");
                    gotoTargets.add(new GotoTarget(stream.lineNumber, Integer.parseInt(stream.expect(TokenKind.NUMBER, null).value)));
                    stream.ensureFinished();
                    return;
                case "end":
                    stream.ensureFinished();
                    return;
                default:
                    throw new AnalysisException(stream.lineNumber, "unsupported command '" + command + "'");
            }
        }

        private void parseExpression(TokenStream stream) throws AnalysisException {
            parseTerm(stream);
            while (true) {
                Token token = stream.peek();
                if (token == null || token.kind != TokenKind.ARITH || (!token.value.equals("+") && !token.value.equals("-"))) {
                    return;
                }
                stream.advance();
                parseTerm(stream);
            }
        }

        private void parseTerm(TokenStream stream) throws AnalysisException {
            parseFactor(stream);
            while (true) {
                Token token = stream.peek();
                if (token == null || token.kind != TokenKind.ARITH
                    || (!token.value.equals("*") && !token.value.equals("/") && !token.value.equals("%"))) {
                    return;
                }
                stream.advance();
                parseFactor(stream);
            }
        }

        private void parseFactor(TokenStream stream) throws AnalysisException {
            Token token = stream.peek();
            if (token == null) {
                throw new AnalysisException(stream.lineNumber, "expected expression");
            }
            if (token.kind == TokenKind.NUMBER) {
                stream.advance();
                return;
            }
            if (token.kind == TokenKind.IDENT) {
                variables.add(stream.advance().value);
                return;
            }
            throw new AnalysisException(stream.lineNumber, "expected number or variable, found '" + token.value + "'");
        }

        private void validateGotos() throws AnalysisException {
            for (GotoTarget gotoTarget : gotoTargets) {
                if (!lineNumbers.contains(gotoTarget.targetLine)) {
                    throw new AnalysisException(gotoTarget.sourceLine, "goto target " + gotoTarget.targetLine + " does not exist");
                }
            }
        }
    }

    private static final class GotoTarget {
        private final int sourceLine;
        private final int targetLine;

        GotoTarget(int sourceLine, int targetLine) {
            this.sourceLine = sourceLine;
            this.targetLine = targetLine;
        }
    }

    private static List<SourceLine> parseSource(String source) throws AnalysisException {
        List<SourceLine> lines = new ArrayList<>();
        Integer previousLineNumber = null;
        String[] rawLines = source.split("\\R", -1);

        for (int physicalLine = 0; physicalLine < rawLines.length; physicalLine++) {
            String rawLine = rawLines[physicalLine];
            if (rawLine.trim().isEmpty()) {
                continue;
            }

            String trimmedLeft = trimLeft(rawLine);
            int separator = firstWhitespace(trimmedLeft);
            String lineNumberText = separator == -1 ? trimmedLeft : trimmedLeft.substring(0, separator);

            if (!isDigitsOnly(lineNumberText)) {
                throw new AnalysisException(physicalLine + 1, "statement must start with a numeric line number");
            }

            int lineNumber = Integer.parseInt(lineNumberText);
            if (previousLineNumber != null && lineNumber <= previousLineNumber) {
                throw new AnalysisException(lineNumber,
                    "line numbers must be strictly increasing (previous was " + previousLineNumber + ")");
            }

            String statement = separator == -1 ? "" : trimmedLeft.substring(separator).trim();
            if (statement.isEmpty()) {
                throw new AnalysisException(lineNumber, "missing command after line number");
            }

            lines.add(new SourceLine(lineNumber, statement));
            previousLineNumber = lineNumber;
        }

        if (lines.isEmpty()) {
            throw new AnalysisException(0, "source file is empty");
        }

        return lines;
    }

    private static List<Token> tokenizeStatement(SourceLine sourceLine) throws AnalysisException {
        String text = trimLeft(sourceLine.text);
        int lineNumber = sourceLine.lineNumber;

        int firstWordEnd = 0;
        while (firstWordEnd < text.length() && Character.isLetter(text.charAt(firstWordEnd))) {
            char current = text.charAt(firstWordEnd);
            if (Character.isUpperCase(current)) {
                throw new AnalysisException(lineNumber, "uppercase letters are only allowed inside rem comments");
            }
            firstWordEnd++;
        }

        String command = text.substring(0, firstWordEnd);
        if (command.isEmpty()) {
            throw new AnalysisException(lineNumber, "missing command");
        }
        String attachedKeyword = findAttachedKeyword(command);
        if (attachedKeyword != null) {
            throw new AnalysisException(lineNumber, "expected whitespace after command '" + attachedKeyword + "'");
        }
        if (!KEYWORDS.contains(command)) {
            throw new AnalysisException(lineNumber, "unknown command '" + command + "'");
        }
        if (command.equals("rem")) {
            if (firstWordEnd < text.length() && Character.isLetterOrDigit(text.charAt(firstWordEnd))) {
                char next = text.charAt(firstWordEnd);
                if (Character.isUpperCase(next)) {
                    throw new AnalysisException(lineNumber, "uppercase letters are only allowed inside rem comments");
                }
                throw new AnalysisException(lineNumber, "expected whitespace after command '" + command + "'");
            }
            return List.of(new Token(TokenKind.KEYWORD, "rem"));
        }
        if (firstWordEnd < text.length() && Character.isLetterOrDigit(text.charAt(firstWordEnd))) {
            char next = text.charAt(firstWordEnd);
            if (Character.isUpperCase(next)) {
                throw new AnalysisException(lineNumber, "uppercase letters are only allowed inside rem comments");
            }
            throw new AnalysisException(lineNumber, "expected whitespace after command '" + command + "'");
        }

        List<Token> tokens = new ArrayList<>();
        tokens.add(new Token(TokenKind.KEYWORD, command));

        int index = firstWordEnd;
        while (index < text.length()) {
            char current = text.charAt(index);

            if (Character.isWhitespace(current)) {
                index++;
                continue;
            }
            if (Character.isUpperCase(current)) {
                throw new AnalysisException(lineNumber, "uppercase letters are only allowed inside rem comments");
            }
            if (Character.isDigit(current)) {
                int start = index;
                while (index < text.length() && Character.isDigit(text.charAt(index))) {
                    index++;
                }
                tokens.add(new Token(TokenKind.NUMBER, text.substring(start, index)));
                continue;
            }
            if (Character.isLowerCase(current)) {
                int start = index;
                while (index < text.length() && Character.isLowerCase(text.charAt(index))) {
                    index++;
                }
                String word = text.substring(start, index);
                if (index < text.length() && Character.isDigit(text.charAt(index))) {
                    while (index < text.length() && Character.isLetterOrDigit(text.charAt(index))) {
                        index++;
                    }
                    String invalidIdentifier = text.substring(start, index);
                    throw new AnalysisException(
                        lineNumber,
                        "invalid identifier '" + invalidIdentifier + "' (variables must be a single lowercase letter)"
                    );
                }
                if (KEYWORDS.contains(word)) {
                    tokens.add(new Token(TokenKind.KEYWORD, word));
                    continue;
                }
                if (word.length() != 1) {
                    throw new AnalysisException(
                        lineNumber,
                        "invalid identifier '" + word + "' (variables must be a single lowercase letter)"
                    );
                }
                tokens.add(new Token(TokenKind.IDENT, word));
                continue;
            }
            if (current == '(' || current == ')') {
                throw new AnalysisException(lineNumber, "parentheses are not allowed in SIMPLE expressions");
            }
            if (current == '+' || current == '-' || current == '*' || current == '/' || current == '%') {
                tokens.add(new Token(TokenKind.ARITH, Character.toString(current)));
                index++;
                continue;
            }
            if (current == '=') {
                if (index + 1 < text.length() && text.charAt(index + 1) == '=') {
                    tokens.add(new Token(TokenKind.RELOP, "=="));
                    index += 2;
                } else {
                    tokens.add(new Token(TokenKind.ASSIGN, "="));
                    index++;
                }
                continue;
            }
            if (current == '<' || current == '>' || current == '!') {
                if (index + 1 < text.length() && text.charAt(index + 1) == '=') {
                    String operator = text.substring(index, index + 2);
                    if (RELATIONAL_OPERATORS.contains(operator)) {
                        tokens.add(new Token(TokenKind.RELOP, operator));
                        index += 2;
                        continue;
                    }
                }
                if (current == '<' || current == '>') {
                    tokens.add(new Token(TokenKind.RELOP, Character.toString(current)));
                    index++;
                    continue;
                }
                throw new AnalysisException(lineNumber, "invalid token starting at '" + text.substring(index) + "'");
            }
            throw new AnalysisException(lineNumber, "invalid character '" + current + "'");
        }

        return tokens;
    }

    private static String readSource(String[] args) throws IOException {
        if (args.length > 1) {
            throw new IllegalArgumentException("Usage: java Main [source.simple]");
        }
        if (args.length == 1) {
            return Files.readString(Path.of(args[0]), StandardCharsets.UTF_8);
        }
        return new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
    }

    private static String trimLeft(String value) {
        int index = 0;
        while (index < value.length() && Character.isWhitespace(value.charAt(index))) {
            index++;
        }
        return value.substring(index);
    }

    private static int firstWhitespace(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isWhitespace(value.charAt(index))) {
                return index;
            }
        }
        return -1;
    }

    private static boolean isDigitsOnly(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            if (!Character.isDigit(value.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    private static String findAttachedKeyword(String command) {
        for (String keyword : KEYWORDS) {
            if (command.startsWith(keyword) && command.length() > keyword.length()) {
                return keyword;
            }
        }
        return null;
    }

    public static void main(String[] args) {
        try {
            String source = readSource(args);
            Analyzer analyzer = new Analyzer();
            System.out.println(analyzer.analyze(source));
        } catch (IllegalArgumentException error) {
            System.err.println(error.getMessage());
            System.exit(1);
        } catch (IOException error) {
            System.err.println("Error: could not open source file");
            System.exit(1);
        } catch (AnalysisException error) {
            System.err.println("Error: " + error.getMessage());
            System.exit(1);
        }
    }
}
