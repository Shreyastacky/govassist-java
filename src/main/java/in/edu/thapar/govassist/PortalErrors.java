package in.edu.thapar.govassist;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;

@ControllerAdvice
public class PortalErrors {
    private static final Logger LOG = LoggerFactory.getLogger(PortalErrors.class);

    @ExceptionHandler(DataAccessException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    String databaseUnavailable(DataAccessException error) {
        LOG.error("Database operation failed ({})", error.getClass().getSimpleName());
        return "error/503";
    }
}
