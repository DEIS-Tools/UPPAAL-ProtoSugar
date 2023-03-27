package uppaal.model

import org.simpleframework.xml.Root

@Root(name = "committed")
class Committed : UppaalPojo{
    override var parent: UppaalPojo? = null

    fun clone(): Committed = Committed()
}