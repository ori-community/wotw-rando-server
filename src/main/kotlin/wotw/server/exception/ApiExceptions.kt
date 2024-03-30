package wotw.server.exception

class UnauthorizedException : Exception()
class ServerConfigurationException(message: String? = null) : Exception(message)
class MissingScopeException(scopes: Collection<String> = emptySet()) :
    Exception(
        if (!scopes.isEmpty()) {
            "The Resource cannot be accessed. Missing scopes: ${scopes.toList().sorted()}"
        } else {
            "The Resource cannot be accessed."
        }
    )
class ConflictException(message: String?) : Exception(message)
class ForbiddenException(message: String?) : Exception(message)
