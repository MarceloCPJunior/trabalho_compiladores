import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Main {
    private static final Set<String> KEYWORDS = Set.of("rem", "input", "let", "print", "goto", "if", "end");
    private static final Set<String> RELATIONAL_OPERATORS = Set.of(">", ">=", "<", "<=", "==", "!=");
    private static final Pattern SOURCE_LINE_PATTERN = Pattern.compile("^\\s*(\\d+)(?:\\s+(.*))?$");

    private enum TokenKind {
        KEYWORD,
        COMMENT,
        IDENT,
        NUMBER,
        ASSIGN,
        ARITH,
        RELOP
    }

    private static final class AnalysisException extends Exception {
        AnalysisException(int lineNumber, String message) {
            super("Linha " + lineNumber + ": " + message);
        }
    }

    private static final class InputReadException extends Exception {
        InputReadException(String message, Throwable cause) {
            super(message, cause);
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
                throw new AnalysisException(lineNumber, "fim inesperado do comando");
            }
            position++;
            return token;
        }

        Token expect(TokenKind kind, String value) throws AnalysisException {
            Token token = advance();
            if (token.kind != kind || (value != null && !value.equals(token.value))) {
                String expected = value != null ? value : describeToken(kind);
                throw new AnalysisException(lineNumber, "esperado " + expected + ", encontrado '" + token.value + "'");
            }
            return token;
        }

        void ensureFinished() throws AnalysisException {
            Token token = peek();
            if (token != null) {
                throw new AnalysisException(lineNumber, "token inesperado '" + token.value + "' no fim do comando");
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

            String variableList = variables.isEmpty() ? "(nenhuma)" : String.join(", ", variables);
            return "Análise concluída com sucesso.\n"
                + "Instruções: " + program.size() + "\n"
                + "Variáveis: " + variableList;
        }

        private void parseStatement(TokenStream stream) throws AnalysisException {
            String command = stream.expect(TokenKind.KEYWORD, null).value;

            switch (command) {
                case "rem":
                    if (stream.peek() != null) {
                        stream.expect(TokenKind.COMMENT, null);
                    }
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
                        throw new AnalysisException(stream.lineNumber, "print aceita apenas uma variável");
                    }
                    return;
                case "goto":
                    gotoTargets.add(new GotoTarget(stream.lineNumber, parseCheckedInteger(
                        stream.expect(TokenKind.NUMBER, null).value,
                        stream.lineNumber,
                        "goto target"
                    )));
                    stream.ensureFinished();
                    return;
                case "if":
                    parseExpression(stream);
                    if (stream.peek() != null && stream.peek().kind == TokenKind.ASSIGN) {
                        throw new AnalysisException(stream.lineNumber, "'=' é válido apenas para atribuição; use '==' em condições");
                    }
                    Token operator = stream.expect(TokenKind.RELOP, null);
                    if (!RELATIONAL_OPERATORS.contains(operator.value)) {
                        throw new AnalysisException(stream.lineNumber, "operador relacional inválido '" + operator.value + "'");
                    }
                    parseExpression(stream);
                    stream.expect(TokenKind.KEYWORD, "goto");
                    gotoTargets.add(new GotoTarget(stream.lineNumber, parseCheckedInteger(
                        stream.expect(TokenKind.NUMBER, null).value,
                        stream.lineNumber,
                        "goto target"
                    )));
                    stream.ensureFinished();
                    return;
                case "end":
                    stream.ensureFinished();
                    return;
                default:
                    throw new AnalysisException(stream.lineNumber, "comando não suportado '" + command + "'");
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
                throw new AnalysisException(stream.lineNumber, "esperava uma expressão");
            }
            if (token.kind == TokenKind.ARITH && (token.value.equals("+") || token.value.equals("-"))) {
                stream.advance();
                parseFactor(stream);
                return;
            }
            if (token.kind == TokenKind.NUMBER) {
                stream.advance();
                return;
            }
            if (token.kind == TokenKind.IDENT) {
                variables.add(stream.advance().value);
                return;
            }
            throw new AnalysisException(stream.lineNumber, "esperava número ou variável, encontrado '" + token.value + "'");
        }

        private void validateGotos() throws AnalysisException {
            for (GotoTarget gotoTarget : gotoTargets) {
                if (!lineNumbers.contains(gotoTarget.targetLine)) {
                    throw new AnalysisException(gotoTarget.sourceLine, "destino de goto " + gotoTarget.targetLine + " não existe");
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

            Matcher matcher = SOURCE_LINE_PATTERN.matcher(rawLine);
            if (!matcher.matches()) {
                throw new AnalysisException(physicalLine + 1, "cada instrução deve começar com um número de linha");
            }

            String lineNumberText = matcher.group(1);
            int lineNumber = parseCheckedInteger(lineNumberText, physicalLine + 1, "line number");
            if (previousLineNumber != null && lineNumber <= previousLineNumber) {
                throw new AnalysisException(
                    physicalLine + 1,
                    "número da linha " + lineNumber + " deve estar em ordem estritamente crescente (anterior foi " + previousLineNumber + ")"
                );
            }

            String statement = matcher.group(2) == null ? "" : matcher.group(2).trim();
            if (statement.isEmpty()) {
                throw new AnalysisException(lineNumber, "comando ausente após o número da linha");
            }

            lines.add(new SourceLine(lineNumber, statement));
            previousLineNumber = lineNumber;
        }

        if (lines.isEmpty()) {
            throw new AnalysisException(0, "o arquivo fonte está vazio");
        }

        return lines;
    }

    private static List<Token> tokenizeStatement(SourceLine sourceLine) throws AnalysisException {
        String text = sourceLine.text.stripLeading();
        int lineNumber = sourceLine.lineNumber;

        int firstWordEnd = 0;
        while (firstWordEnd < text.length() && Character.isLetter(text.charAt(firstWordEnd))) {
            char current = text.charAt(firstWordEnd);
            if (Character.isUpperCase(current)) {
                throw new AnalysisException(lineNumber, "letras maiúsculas são permitidas apenas em comentários rem");
            }
            firstWordEnd++;
        }

        String command = text.substring(0, firstWordEnd);
        if (command.isEmpty()) {
            throw new AnalysisException(lineNumber, "comando ausente");
        }
        String attachedKeyword = findAttachedKeyword(command);
        if (attachedKeyword != null) {
            throw new AnalysisException(lineNumber, "esperado espaço em branco após o comando '" + attachedKeyword + "'");
        }
        if (!KEYWORDS.contains(command)) {
            throw new AnalysisException(lineNumber, "comando desconhecido '" + command + "'");
        }
        if (command.equals("rem")) {
            if (firstWordEnd < text.length() && Character.isLetterOrDigit(text.charAt(firstWordEnd))) {
                char next = text.charAt(firstWordEnd);
                if (Character.isUpperCase(next)) {
                    throw new AnalysisException(lineNumber, "letras maiúsculas são permitidas apenas em comentários rem");
                }
                throw new AnalysisException(lineNumber, "esperado espaço em branco após o comando '" + command + "'");
            }
            String comment = firstWordEnd >= text.length() ? "" : text.substring(firstWordEnd).stripLeading();
            if (comment.isEmpty()) {
                return List.of(new Token(TokenKind.KEYWORD, "rem"));
            }
            return List.of(new Token(TokenKind.KEYWORD, "rem"), new Token(TokenKind.COMMENT, comment));
        }
        if (firstWordEnd < text.length() && Character.isLetterOrDigit(text.charAt(firstWordEnd))) {
            char next = text.charAt(firstWordEnd);
            if (Character.isUpperCase(next)) {
                throw new AnalysisException(lineNumber, "letras maiúsculas são permitidas apenas em comentários rem");
            }
            throw new AnalysisException(lineNumber, "esperado espaço em branco após o comando '" + command + "'");
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
                throw new AnalysisException(lineNumber, "letras maiúsculas são permitidas apenas em comentários rem");
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
                        "identificador inválido '" + invalidIdentifier + "' (variáveis devem ter apenas uma letra minúscula)"
                    );
                }
                if (KEYWORDS.contains(word)) {
                    tokens.add(new Token(TokenKind.KEYWORD, word));
                    continue;
                }
                if (word.length() != 1) {
                    throw new AnalysisException(
                        lineNumber,
                        "identificador inválido '" + word + "' (variáveis devem ter apenas uma letra minúscula)"
                    );
                }
                tokens.add(new Token(TokenKind.IDENT, word));
                continue;
            }
            if (current == '(' || current == ')') {
                throw new AnalysisException(lineNumber, "parênteses não são permitidos em expressões SIMPLE");
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
                throw new AnalysisException(lineNumber, "token inválido a partir de '" + text.substring(index) + "'");
            }
            throw new AnalysisException(lineNumber, "caractere inválido '" + current + "'");
        }

        return tokens;
    }

    private static String readSource(String[] args) throws InputReadException {
        if (args.length > 1) {
            throw new IllegalArgumentException("Uso: java Main [arquivo.simple | -]");
        }
        if (args.length == 1 && !args[0].equals("-")) {
            try {
                return Files.readString(Path.of(args[0]), StandardCharsets.UTF_8);
            } catch (IOException error) {
                throw new InputReadException("Erro: não foi possível abrir '" + args[0] + "'", error);
            }
        }
        try {
            return new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new InputReadException("Erro: não foi possível ler a entrada padrão", error);
        }
    }

    private static String findAttachedKeyword(String command) {
        for (String keyword : KEYWORDS) {
            if (command.startsWith(keyword) && command.length() > keyword.length()) {
                return keyword;
            }
        }
        return null;
    }

    private static int parseCheckedInteger(String value, int lineNumber, String description) throws AnalysisException {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException error) {
            throw new AnalysisException(lineNumber, describeNumericField(description) + " '" + value + "' é grande demais");
        }
    }

    private static String describeToken(TokenKind kind) {
        switch (kind) {
            case KEYWORD:
                return "comando";
            case COMMENT:
                return "comentário";
            case IDENT:
                return "identificador";
            case NUMBER:
                return "número";
            case ASSIGN:
                return "=";
            case ARITH:
                return "operador aritmético";
            case RELOP:
                return "operador relacional";
            default:
                return "token";
        }
    }

    private static String describeNumericField(String description) {
        if ("line number".equals(description)) {
            return "número da linha";
        }
        if ("goto target".equals(description)) {
            return "destino de goto";
        }
        return description;
    }

    public static void main(String[] args) {
        try {
            String source = readSource(args);
            Analyzer analyzer = new Analyzer();
            System.out.println(analyzer.analyze(source));
        } catch (IllegalArgumentException error) {
            System.err.println(error.getMessage());
            System.exit(1);
        } catch (InputReadException error) {
            System.err.println(error.getMessage());
            System.exit(1);
        } catch (AnalysisException error) {
            System.err.println("Erro: " + error.getMessage());
            System.exit(1);
        }
    }
}
