package io.formkiln.server.web;

import io.formkiln.server.form.AnswerValidator.FormEvaluation;
import io.formkiln.server.forms.FormService.InvalidAnswersException;
import io.formkiln.server.forms.FormService.InvalidFormException;
import io.formkiln.server.forms.FormService.NotFoundException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps domain exceptions to RFC 9457 problem details. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail notFound(NotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(InvalidFormException.class)
    ProblemDetail invalidForm(InvalidFormException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_CONTENT, "The form document is not valid.");
        problem.setTitle("Invalid form document");
        problem.setProperty("errors", e.errors());
        return problem;
    }

    @ExceptionHandler(InvalidAnswersException.class)
    ProblemDetail invalidAnswers(InvalidAnswersException e) {
        FormEvaluation evaluation = e.evaluation();
        List<Map<String, Object>> errors = evaluation.errors().stream()
                .map(error -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("path", error.path());
                    item.put("code", error.code());
                    if (error.message() != null) {
                        item.put("message", error.message());
                    }
                    if (error.params() != null) {
                        item.put("params", error.params());
                    }
                    return item;
                })
                .toList();
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_CONTENT, "The answers are not valid.");
        problem.setTitle("Invalid answers");
        problem.setProperty("errors", errors);
        return problem;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail unreadable(HttpMessageNotReadableException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "The request body is not valid JSON.");
    }
}
