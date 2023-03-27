package uppaal.visitors

import uppaal.UppaalPath
import uppaal.model.UppaalPojo

class ParentVisitor : NtaVisitorBase() {
    override fun visitUppaalPojo(path: UppaalPath, uppaalPojo: UppaalPojo) {
        uppaalPojo.parent = path.takeLast(2).dropLast(1).firstOrNull()?.element
    }
}