package mapping.parsing

@Suppress("MemberVisibilityCanBePrivate")
class ConfreHelper {
    companion object {
        val expressionGrammar = """
                IDENT = [_a-zA-Z][_a-zA-Z0-9]*
                INT = [0-9]+
                BOOL = true|false
            
                Expression :== [Unary] ('(' Expression ')' | (Term [{Array} | '(' [Expression {',' Expression}] ')'])) [(Binary|Assignment) Expression] .
                Term       :== IDENT | INT | BOOL .
                Unary      :== '+' | '-' | '!' | 'not' .
                Binary     :== '<' | '<=' | '==' | '!=' | '>=' | '>' | '+' | '-' | '*' | '/' | '%' | '&'
                             | '|' | '^' | '<<' | '>>' | '&&' | '||' | '<?' | '>?' | 'or' | 'and' | 'imply' .
                Assignment :== '=' | ':=' | '+=' | '-=' | '*=' | '/=' | '%=' | '|=' | '&=' | '^=' | '<<=' | '>>=' .
                Array  :== '[' [Expression] ']' .
            """.trimIndent()

        // TODO: Support for structs?
        val regularTypeAndExpressionGrammar = """
            Type   :== ['const'] IDENT ['[' [Expression] ',' [Expression] ']' | '(' [Type] {',' [Type]} ')'] .
            
            $expressionGrammar
        """.trimIndent()



        val partialInstantiationConfre = Confre("""
            Instantiation :== IDENT ['(' [Params] ')'] '=' IDENT '(' [[Expression] {',' [Expression]}] ')' ';'.
            Params :== [Type ['&'] IDENT {Array}] {',' [Type ['&'] IDENT {Array}]} .
            
            $regularTypeAndExpressionGrammar
        """.trimIndent())

        val constIntConfre = Confre(
            """
            ConstInt :== 'const' 'int' ['[' [Expression] ',' [Expression] ']'] IDENT '=' Expression ';' .
            
            $expressionGrammar
        """.trimIndent())

        val expressionAssignmentListConfre = Confre("""
            ExprList :== Expression {',' Expression} .
            
            $expressionGrammar
        """.trimIndent())

        val systemLineConfre = Confre("""
            IDENT = [_a-zA-Z][_a-zA-Z0-9]*
            
            System :== 'system' IDENT {',' IDENT} .
        """.trimIndent())
    }
}