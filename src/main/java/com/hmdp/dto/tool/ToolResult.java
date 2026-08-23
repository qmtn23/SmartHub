package com.hmdp.dto.tool;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ToolResult<T> {
    private boolean success;
    private String code;
    private String message;
    private T data;
    private boolean retryable;
    private List<BusinessReferenceDTO> bizRefs;

    public static <T> ToolResult<T> success(T data, String message,
                                            List<BusinessReferenceDTO> bizRefs) {
        return new ToolResult<>(true, ToolResultCodes.SUCCESS, message, data,
                false, bizRefs == null ? Collections.emptyList() : bizRefs);
    }

    public static <T> ToolResult<T> failure(String code, String message, boolean retryable) {
        return new ToolResult<>(false, code, message, null, retryable, Collections.emptyList());
    }
}
