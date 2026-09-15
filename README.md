# trabalho_compiladores

Analisador léxico, sintático e semântico para a linguagem SIMPLE, pensado para execução direta no GDB Online com Java puro.

## Como executar

```bash
javac Main.java
java Main samples/valid/program_ok.simple
```

Também é possível enviar o código-fonte pela entrada padrão:

```bash
javac Main.java
java Main < samples/valid/program_ok.simple
```

Ou, se preferir explicitar a leitura pela entrada padrão:

```bash
javac Main.java
java Main - < samples/valid/program_ok.simple
```

No GDB Online, finalize a entrada padrão com EOF para encerrar a leitura.

## Validar os exemplos do repositório

```bash
chmod +x validate_samples.sh
./validate_samples.sh
```

## Regras validadas

- cada linha deve começar com um número inteiro;
- os números das linhas devem estar em ordem estritamente crescente;
- comandos válidos: `rem`, `input`, `let`, `print`, `goto`, `if ... goto`, `end`;
- letras maiúsculas geram erro fora de comentários `rem`;
- variáveis são apenas uma letra minúscula;
- expressões inteiras aceitam `+`, `-`, `*`, `/`, `%`, sem uso de parênteses;
- `print` aceita apenas uma variável;
- destinos de `goto` e `if ... goto` devem apontar para linhas existentes.

## Resultado esperado

- em programas válidos, o analisador informa sucesso, quantidade de instruções e variáveis encontradas;
- em programas inválidos, o analisador informa a linha e o motivo do erro, sempre em português brasileiro.

## Casos de teste incluídos

### Programa válido

- `samples/valid/program_ok.simple`
- saída esperada: `samples/valid/program_ok.expected`

### Inconsistências exigidas

1. comando inválido: `samples/invalid/01_invalid_command.simple`
2. uso de maiúsculas fora de `rem`: `samples/invalid/02_uppercase_usage.simple`
3. `if` sem `goto`: `samples/invalid/03_missing_goto.simple`
4. desvio para linha inexistente: `samples/invalid/04_invalid_target.simple`
5. uso indevido de parênteses: `samples/invalid/05_parentheses_not_allowed.simple`
6. `print` com expressão em vez de variável: `samples/invalid/06_print_requires_variable.simple`
7. números de linha fora de ordem crescente: `samples/invalid/07_non_increasing_lines.simple`
8. linha sem comando após o número: `samples/invalid/08_missing_command.simple`

Cada caso possui um arquivo `.expected` com a saída esperada.