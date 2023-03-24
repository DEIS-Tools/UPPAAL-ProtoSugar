package uppaal.model

import org.simpleframework.xml.Root

@Root(name = "urgent")
class Urgent : UppaalPojo {
    fun clone(): Urgent = Urgent()
}