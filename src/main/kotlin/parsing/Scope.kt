package parsing

import uppaal_pojo.Nta
import uppaal_pojo.Template

interface Scope {
    val parent: Scope?
    val declarations: ArrayList<Declaration>
}

interface ParametrizedScope : Scope {
    val parameters: ArrayList<Declaration>
}

interface ParallelComposedScope : Scope {
    val subScopes: ArrayList<Scope>
    val systemDeclarations: ArrayList<Declaration>
    val systemLine: ArrayList<String>
}

class ProcessTemplateScope(val obj: Template, override val parent: Scope) : ParametrizedScope {
    override val parameters: ArrayList<Declaration> = ArrayList()
    override val declarations: ArrayList<Declaration> = ArrayList()
}

class GlobalScope(val obj: Nta) : ParallelComposedScope {
    override val parent: Scope? = null;
    override val declarations: ArrayList<Declaration> = ArrayList()
    override val subScopes: ArrayList<Scope> = ArrayList()
    override val systemDeclarations: ArrayList<Declaration> = ArrayList()
    override val systemLine: ArrayList<String> = ArrayList()
}