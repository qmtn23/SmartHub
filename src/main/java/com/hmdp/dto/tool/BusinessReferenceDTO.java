package com.hmdp.dto.tool;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BusinessReferenceDTO {
    private String bizType;
    private Long bizId;
}
