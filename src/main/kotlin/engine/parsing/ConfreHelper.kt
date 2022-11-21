package engine.parsing

@Suppress("MemberVisibilityCanBePrivate")
class ConfreHelper {
    companion object {
        val expressionGrammarString = """
                Expression :== [Unary] ('(' Expression ')' | (Term [{Array} | '(' [Expression {',' Expression}] ')'])) [(Binary|Assignment) Expression] .
                Term       :== IDENT | INT | BOOL .
                Unary      :== '+' | '-' | '!' | 'not' .
                Binary     :== '<' | '<=' | '==' | '!=' | '>=' | '>' | '+' | '-' | '*' | '/' | '%' | '&'
                             | '|' | '^' | '<<' | '>>' | '&&' | '||' | '<?' | '>?' | 'or' | 'and' | 'imply' .
                Assignment :== '=' | ':=' | '+=' | '-=' | '*=' | '/=' | '%=' | '|=' | '&=' | '^=' | '<<=' | '>>=' .
                Array  :== '[' [Expression] ']' .
            """.trimIndent()

        val partialInstantiationGrammar = Confre("""
            IDENT = [_a-zA-Z][_a-zA-Z0-9]*
            INT = [0-9]+
            BOOL = true|false

            Instantiation :== IDENT ['(' [Params] ')'] '=' IDENT '(' [[Expression] {',' [Expression]}] ')' ';'.
            
            Params :== [Type IDENT {Array}] {',' [Type IDENT {Array}]} .
            Type   :== ['const'] IDENT ['[' [Expression] ',' [Expression] ']'].
            
            $expressionGrammarString
        """.trimIndent())

        val constIntGrammar = Confre(
            """
            IDENT = [_a-zA-Z][_a-zA-Z0-9]*
            INT = -?[0-9]+
            
            ConstInt  :== 'const' 'int' IDENT '=' INT ';' .
        """.trimIndent())
    }
}