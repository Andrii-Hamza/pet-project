package com.petproject.ecomnerce.handler;

import java.util.Map;

public record ErrorResponse(
        Map<String, String> errors
) {

}