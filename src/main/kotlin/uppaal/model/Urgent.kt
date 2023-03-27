package uppaal.model

import org.simpleframework.xml.Root

@Root(name = "urgent")
class Urgent : UppaalPojo {
    override var parent: UppaalPojo? = null


    fun clone(): Urgent = Urgent()
}