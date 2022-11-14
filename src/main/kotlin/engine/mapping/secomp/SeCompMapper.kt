package engine.mapping.secomp

import engine.mapping.*
import uppaal_pojo.Template

class SeCompMapper : Mapper {
    override fun getPhases(): Pair<Sequence<ModelPhase>, QueryPhase?>
        = Pair(sequenceOf(Phase1()), null)

    private class Phase1 : ModelPhase() {
        init {
            register(::mapTemplate)
        }

        private fun mapTemplate(path: List<PathNode>, template: Template): List<UppaalError> {
            // TODO
            return listOf()
        }


        override fun mapModelErrors(errors: List<UppaalError>): List<UppaalError> {
            return errors
        }
    }
}