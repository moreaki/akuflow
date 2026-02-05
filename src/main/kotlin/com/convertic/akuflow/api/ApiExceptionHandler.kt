package com.convertic.akuflow.api

import com.convertic.akuflow.bpmn.runtime.BpmnCompilationException
import com.convertic.akuflow.bpmn.runtime.DefinitionNotFoundException
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.ErrorResponse
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(DefinitionNotFoundException::class)
    fun handleNotFound(ex: DefinitionNotFoundException): ResponseEntity<ProblemDetail> =
        problem(HttpStatus.NOT_FOUND, ex.message ?: "Not found")

    @ExceptionHandler(BpmnCompilationException::class, IllegalArgumentException::class)
    fun handleBadRequest(ex: RuntimeException): ResponseEntity<ProblemDetail> =
        problem(HttpStatus.BAD_REQUEST, ex.message ?: "Bad request")

    @ExceptionHandler(Exception::class)
    fun handleServerError(ex: Exception): ResponseEntity<ProblemDetail> =
        if (ex is ErrorResponse) {
            val pd = ex.body ?: ProblemDetail.forStatus(ex.statusCode)
            if (pd.detail == null) {
                pd.detail = ex.message
            }
            ResponseEntity.status(ex.statusCode)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(pd)
        } else {
            problem(HttpStatus.INTERNAL_SERVER_ERROR, ex.message ?: "Unexpected error")
        }

    private fun problem(status: HttpStatus, detail: String): ResponseEntity<ProblemDetail> {
        val pd = ProblemDetail.forStatusAndDetail(status, detail)
        pd.title = status.reasonPhrase
        return ResponseEntity.status(status)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(pd)
    }
}
