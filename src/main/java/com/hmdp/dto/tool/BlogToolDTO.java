package com.hmdp.dto.tool;

import lombok.Data;

@Data
public class BlogToolDTO {
    private Long blogId;
    private String title;
    private Integer liked;
    private Integer comments;
    private String contentSummary;
}
