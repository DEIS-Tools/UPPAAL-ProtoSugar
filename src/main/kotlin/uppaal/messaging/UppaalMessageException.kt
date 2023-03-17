package uppaal.messaging

/** This exception is used to "return" uppaal errors from complex chains of function calls without having to insert logic
 * to return nullable errors when this results in unreadable code. **/
class UppaalMessageException(val uppaalMessage: UppaalMessage) : Exception()