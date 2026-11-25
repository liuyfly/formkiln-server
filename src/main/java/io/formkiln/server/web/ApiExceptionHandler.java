package io.formkiln.server.web;

import io.formkiln.server.accounts.AccountService.AccountException;
import io.formkiln.server.form.AnswerValidator.FormEvaluation;
import io.formkiln.server.forms.FormService.InvalidAnswersException;
import io.formkiln.server.forms.FormService.InvalidFormException;
import io.formkiln.server.forms.FormService.NotFoundException;
import io.formkiln.server.forms.WorkingCopyService.ConflictException;
import io.formkiln.server.platform.RuntimeException4xx;
import io.formkiln.server.platform.WebhookService.InvalidWebhookException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

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

    @ExceptionHandler(AccountException.class)
    ProblemDetail account(AccountException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(e.status(), e.getMessage());
        problem.setProperty("code", e.code());
        return problem;
    }

    @ExceptionHandler(RuntimeException4xx.class)
    ProblemDetail runtime(RuntimeException4xx e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(e.status(), e.getMessage());
        problem.setProperty("code", e.code());
        if (e instanceof ConflictException conflict) {
            conflict.details().forEach(problem::setProperty);
        }
        return problem;
    }

    @ExceptionHandler(InvalidWebhookException.class)
    ProblemDetail invalidWebhook(InvalidWebhookException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setProperty("code", "invalidWebhookUrl");
        return problem;
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ProblemDetail tooLarge(MaxUploadSizeExceededException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONTENT_TOO_LARGE, "The file is too large.");
        problem.setProperty("code", "fileTooLarge");
        return problem;
    }

    @ExceptionHandler({MissingServletRequestPartException.class, MissingServletRequestParameterException.class})
    ProblemDetail missingPart(Exception e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail unreadable(HttpMessageNotReadableException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "The request body is not valid JSON.");
    }
}
