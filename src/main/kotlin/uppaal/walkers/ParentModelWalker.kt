package uppaal.walkers

import uppaal.UppaalPath
import uppaal.model.UppaalPojo

class ParentModelWalker : ModelWalkerBase() {
    override fun visitUppaalPojo(path: UppaalPath, uppaalPojo: UppaalPojo) {
        uppaalPojo.parent = path.takeLast(2).dropLast(1).firstOrNull()?.element
    }
}