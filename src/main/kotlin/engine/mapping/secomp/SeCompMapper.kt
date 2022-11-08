package engine.mapping.secomp

import engine.mapping.Mapper
import engine.mapping.MapperError
import engine.mapping.PathNode
import engine.mapping.Phase
import uppaal_pojo.Template

class SeCompMapper : Mapper {
    override fun getPhases(): Sequence<Phase> = sequenceOf(Phase1())

    private class Phase1 : Phase() {
        init {
            register(::mapTemplate)
        }

        private fun mapTemplate(path: List<PathNode>, template: Template): List<MapperError> {
            // TODO
            return listOf()
        }
    }
}