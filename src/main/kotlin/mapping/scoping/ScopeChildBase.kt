package mapping.scoping

abstract class ScopeChildBase {
    var parent: Scope? = null
        private set

    /** Even if 'parent' is set as 'protected set', it would be impossible to set the parent of a Declaration from a
     *  Scope due to Kotlin's visibility rules. This is the only way of hiding these details away without making
     *  parent 'public set', in which case everyone can mess with the parent. **/
    protected fun setThisAsParentOf(other: ScopeChildBase) {
        other.parent = this as Scope
    }
}