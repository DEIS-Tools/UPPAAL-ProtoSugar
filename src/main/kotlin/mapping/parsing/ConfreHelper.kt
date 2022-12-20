package mapping.parsing

class ConfreHelper {
    companion object {
        private fun expressionGenerator(withDotting: Boolean): String {
            val dotting = if (withDotting) "{'.' [ExtendedTerm]}" else ""
            return """
                IDENT = [_a-zA-Z][_a-zA-Z0-9]*
                INT   = [0-9]+
                BOOL  = true|false
            
                Expression   :== [Unary] ('(' Expression ')' | ExtendedTerm $dotting | Quantifier) [(Binary|Assignment) Expression] .
                ExtendedTerm :== Term [{Subscript} | '(' [Expression {',' Expression}] ')'] .
                Term         :== IDENT | INT | BOOL | 'deadlock' .
                Subscript    :== '[' [Expression] ']' .
                
                Quantifier :== ('forall' | 'exists' | 'sum') '(' IDENT ':' Type ')' Expression .
                
                Unary      :== '+' | '-' | '!' | 'not' .
                Binary     :== '<' | '<=' | '==' | '!=' | '>=' | '>' | '+' | '-' | '*' | '/' | '%' | '&'
                             | '|' | '^' | '<<' | '>>' | '&&' | '||' | '<?' | '>?' | 'or' | 'and' | 'imply' .
                Assignment :== '=' | ':=' | '+=' | '-=' | '*=' | '/=' | '%=' | '|=' | '&=' | '^=' | '<<=' | '>>=' .
                
                Type :== ['const'] IDENT ['[' [Expression] ',' [Expression] ']' | '(' [Type] {',' [Type]} ')'] .
            """.trimIndent() // TODO: Support for structs in "Type"?
        }

        val queryExpressionGrammar = expressionGenerator(true)
        val baseExpressionGrammar = expressionGenerator(false)

        val partialInstantiationConfre = Confre("""
            Instantiation :== IDENT ['(' [Params] ')'] '=' IDENT '(' [[Expression] {',' [Expression]}] ')' ';'.
            Params :== [Type ['&'] IDENT {Subscript}] {',' [Type ['&'] IDENT {Subscript}]} .
            
            $baseExpressionGrammar
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