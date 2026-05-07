package com.securitysystem.dto;

import com.securitysystem.model.User;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class UserDto {
    private String username;
    private String role;

    public static UserDto from(User user) {
        return new UserDto(user.getUsername(), user.getRole());
    }
}
