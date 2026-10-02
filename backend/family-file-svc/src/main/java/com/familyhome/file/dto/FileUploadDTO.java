package com.familyhome.file.dto;

import jakarta.validation.constraints.*;
import org.springframework.web.multipart.MultipartFile;

public record FileUploadDTO(
        @NotNull MultipartFile file, @NotBlank @Pattern(regexp = "FAMILY|PRIVATE") String visibility) {}
