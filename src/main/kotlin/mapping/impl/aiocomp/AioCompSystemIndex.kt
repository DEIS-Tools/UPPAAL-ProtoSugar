package mapping.impl.aiocomp

import mapping.base.UppaalProcess

class AioCompSystemIndex(private val modelIndex: AioCompModelIndex) {
    val baseProcessNameToTemplateInfo = HashMap<String, TaTemplateInfo>()

    fun tryRegister(process: UppaalProcess): TaTemplateInfo? {
        val baseName = process.name.substringBefore('(')
        return baseProcessNameToTemplateInfo.getOrPut(baseName) {
            modelIndex.rootSubTemUsers.values
            .find { it.infixedName == process.template }
            ?: return null
        }
    }
}