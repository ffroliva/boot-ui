package io.github.jdubois.bootui.engine.architecture.kotlinfixtures

import java.time.Instant
import java.util.Date

interface ExternalLegacyTicket {
    val createdAt: Date
    val updatedAt: Date
}

data class TicketResponse(val createdAt: Instant, val updatedAt: Instant)

class LegacyTicketMapper {
    fun toResponse(ticket: ExternalLegacyTicket): TicketResponse =
        TicketResponse(ticket.createdAt.toInstant(), ticket.updatedAt.toInstant())
}

class LegacyDateParameterMapper {
    fun toInstant(date: Date): Instant = date.toInstant()
}
