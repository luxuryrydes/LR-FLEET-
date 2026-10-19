package com.lrtaxi.fleet.users;

import com.lrtaxi.fleet.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService users;

    public AuthController(UserService users) {
        this.users = users;
    }

    public record LoginRequest(@NotBlank @Email String email, @NotBlank @Size(max = 200) String password) {
    }

    public record ChangePasswordRequest(@NotBlank String currentPassword, @NotBlank @Size(min = 10, max = 200) String newPassword) {
    }

    @PostMapping("/login")
    public Map<String, Object> login(@Valid @RequestBody LoginRequest req) {
        return users.login(req.email(), req.password());
    }

    @GetMapping("/me")
    public Map<String, Object> me() {
        return users.profile(CurrentUser.require().id());
    }

    @PostMapping("/change-password")
    public void changePassword(@Valid @RequestBody ChangePasswordRequest req) {
        users.changeOwnPassword(CurrentUser.require().id(), req.currentPassword(), req.newPassword());
    }
}
