package com.emplmgt.service;

import com.emplmgt.dto.AuthDtos;
import com.emplmgt.entity.Employee;
import com.emplmgt.entity.Role;
import com.emplmgt.entity.User;
import com.emplmgt.exception.ApiException;
import com.emplmgt.repository.EmployeeRepository;
import com.emplmgt.repository.UserRepository;
import com.emplmgt.security.JwtService;
import com.emplmgt.util.AppClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock AuthenticationManager authenticationManager;
    @Mock UserRepository userRepository;
    @Mock EmployeeRepository employeeRepository;
    @Mock JwtService jwtService;
    @Mock PasswordEncoder passwordEncoder;
    @Mock AppClock appClock;

    private AuthService service;

    @BeforeEach
    void setUp() {
        service = new AuthService(authenticationManager, userRepository, employeeRepository,
                jwtService, passwordEncoder, appClock);
    }

    @Test
    void loginSucceedsAndReturnsTokenForActiveUser() {
        User user = User.builder().id(1L).email("john@x.com").role(Role.EMPLOYEE).enabled(true).build();
        Employee emp = Employee.builder().id(10L).employeeCode("EMP-001")
                .fullName("John Doe").user(user).build();
        when(userRepository.findByEmailIgnoreCase("john@x.com")).thenReturn(Optional.of(user));
        when(employeeRepository.findByUserId(1L)).thenReturn(Optional.of(emp));
        when(appClock.now()).thenReturn(Instant.parse("2026-09-18T10:00:00Z"));
        when(jwtService.generateToken(1L, "john@x.com", "EMPLOYEE")).thenReturn("token-123");
        when(jwtService.getExpirationMs()).thenReturn(86400000L);

        AuthDtos.LoginResponse response = service.login(new AuthDtos.LoginRequest("john@x.com", "secret", true));

        assertThat(response.token()).isEqualTo("token-123");
        assertThat(response.user().email()).isEqualTo("john@x.com");
        assertThat(response.user().fullName()).isEqualTo("John Doe");
        verify(userRepository).updateLastLogin(1L, Instant.parse("2026-09-18T10:00:00Z"));
    }

    @Test
    void loginRejectsInvalidCredentials() {
        doThrow(new BadCredentialsException("bad creds"))
                .when(authenticationManager).authenticate(any());

        assertThatThrownBy(() -> service.login(new AuthDtos.LoginRequest("john@x.com", "wrong", true)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Invalid email or password");
    }

    @Test
    void loginRejectsDeactivatedAccount() {
        User user = User.builder().id(1L).email("john@x.com").role(Role.EMPLOYEE).enabled(false).build();
        when(userRepository.findByEmailIgnoreCase("john@x.com")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.login(new AuthDtos.LoginRequest("john@x.com", "secret", true)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("deactivated");
    }

    @Test
    void meFallsBackToNullEmployeeFields() {
        User user = User.builder().id(2L).email("admin@x.com").role(Role.ADMIN).enabled(true).build();
        when(userRepository.findById(2L)).thenReturn(Optional.of(user));
        when(employeeRepository.findByUserId(2L)).thenReturn(Optional.empty());

        AuthDtos.MeResponse me = service.me(2L);
        assertThat(me.role()).isEqualTo("ADMIN");
        assertThat(me.employeeCode()).isNull();
    }
}