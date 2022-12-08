package mapping.parsing

@Suppress("MemberVisibilityCanBePrivate")
class ConfreHelper {
    companion object {
        private fun expressionGenerator(withDotting: Boolean): String {
            val dotting = if (withDotting) "{'.' [ExtendedTerm]}" else ""
            return """
                IDENT = [_a-zA-Z][_a-zA-Z0-9]*
                INT   = [0-9]+
                BOOL  = true|false
            
                Expression   :== [Unary] ('(' Expression ')' | ExtendedTerm $dotting) [(Binary|Assignment) Expression] .
                ExtendedTerm :== Term [{Array} | '(' [Expression {',' Expression}] ')'] .
                Term         :== IDENT | INT | BOOL .
                Array        :== '[' [Expression] ']' .
                
                Unary        :== '+' | '-' | '!' | 'not' .
                Binary       :== '<' | '<=' | '==' | '!=' | '>=' | '>' | '+' | '-' | '*' | '/' | '%' | '&'
                               | '|' | '^' | '<<' | '>>' | '&&' | '||' | '<?' | '>?' | 'or' | 'and' | 'imply' .
                Assignment   :== '=' | ':=' | '+=' | '-=' | '*=' | '/=' | '%=' | '|=' | '&=' | '^=' | '<<=' | '>>=' .
            """.trimIndent()
        }

        val queryExpressionGrammar = expressionGenerator(true)
        val baseExpressionGrammar = expressionGenerator(false)

        // TODO: Support for structs?
        val regularTypeAndExpressionGrammar = """
            Type   :== ['const'] IDENT ['[' [Expression] ',' [Expression] ']' | '(' [Type] {',' [Type]} ')'] .
            
            $baseExpressionGrammar
        """.trimIndent()



        val partialInstantiationConfre = Confre("""
            Instantiation :== IDENT ['(' [Params] ')'] '=' IDENT '(' [[Expression] {',' [Expression]}] ')' ';'.
            Params :== [Type ['&'] IDENT {Array}] {',' [Type ['&'] IDENT {Array}]} .
            
            $regularTypeAndExpressionGrammar
        """.trimIndent())

        val constIntConfre = Confre(
            """
            ConstInt :== 'const' 'int' ['[' [Expression] ',' [Expression] ']'] IDENT '=' Expression ';' .
            
            $baseExpressionGrammar
        """.trimIndent())

        val expressionAssignmentListConfre = Confre("""
            ExprList :== Expression {',' Expression} .
            
            $baseExpressionGrammar
        """.trimIndent())

        val systemLineConfre = Confre("""
            IDENT = [_a-zA-Z][_a-zA-Z0-9]*
            
            System :== 'system' IDENT {',' IDENT} .
        """.trimIndent())
    }
}