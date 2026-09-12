// Comprehensive password policy test
const PASSWORD_REGEX = /^(?=.*[0-9])(?=.*[a-z])(?=.*[A-Z])(?=.*[!@#$%^&*()_+\-=\[\]{};':"\\|,.<>\/?~`]).*$/;

export function checkPasswordPolicy(password, confirmPassword = "") {
  return {
    minLength: password.length >= 8 && password.length <= 100,
    hasUpper: /[A-Z]/.test(password),
    hasLower: /[a-z]/.test(password),
    hasNumber: /[0-9]/.test(password),
    hasSpecial: /[!@#$%^&*()_+\-=\[\]{};':"\\|,.<>\/?~`]/.test(password),
    passwordsMatch: password.length > 0 && password === confirmPassword,
    fullRegexValid: PASSWORD_REGEX.test(password),
    isValid: password.length >= 8 &&
             password.length <= 100 &&
             PASSWORD_REGEX.test(password)
  };
}

const testCases = [
  { name: 'valid standard password', pwd: 'Password123!', cpwd: 'Password123!', expected: true },
  { name: 'valid password with question mark', pwd: 'Password123?', cpwd: 'Password123?', expected: true },
  { name: 'valid password with asterisk', pwd: 'Password123*', cpwd: 'Password123*', expected: true },
  { name: 'valid password with tilde', pwd: 'Password123~', cpwd: 'Password123~', expected: true },
  { name: 'valid password with parens', pwd: 'Password(123)', cpwd: 'Password(123)', expected: true },
  { name: 'valid password with slash', pwd: 'Password/123', cpwd: 'Password/123', expected: true },
  { name: 'valid password with brackets', pwd: 'Password[123]', cpwd: 'Password[123]', expected: true },
  { name: 'valid password with curly braces', pwd: 'Password{123}', cpwd: 'Password{123}', expected: true },
  { name: 'valid password with angle brackets', pwd: 'Password<123>', cpwd: 'Password<123>', expected: true },
  { name: 'valid password with pipe', pwd: 'Password|123', cpwd: 'Password|123', expected: true },
  { name: 'valid password with colon', pwd: 'Password:123', cpwd: 'Password:123', expected: true },
  { name: 'valid password with semicolon', pwd: 'Password;123', cpwd: 'Password;123', expected: true },
  { name: 'valid password with comma', pwd: 'Password,123', cpwd: 'Password,123', expected: true },
  { name: 'valid password with backtick', pwd: 'Password`123', cpwd: 'Password`123', expected: true },
  { name: 'valid password with double quote', pwd: 'Password"123', cpwd: 'Password"123', expected: true },
  { name: 'valid password with single quote', pwd: "Password'123", cpwd: "Password'123", expected: true },
  { name: 'lowercase only', pwd: 'passwordonly', cpwd: 'passwordonly', expected: false },
  { name: 'uppercase only', pwd: 'PASSWORDONLY', cpwd: 'PASSWORDONLY', expected: false },
  { name: 'numbers only', pwd: '1234567890', cpwd: '1234567890', expected: false },
  { name: 'missing uppercase', pwd: 'password123!', cpwd: 'password123!', expected: false },
  { name: 'missing lowercase', pwd: 'PASSWORD123!', cpwd: 'PASSWORD123!', expected: false },
  { name: 'missing number', pwd: 'Password!!!!', cpwd: 'Password!!!!', expected: false },
  { name: 'missing special character', pwd: 'Password123', cpwd: 'Password123', expected: false },
  { name: 'too short (< 8 chars)', pwd: 'Pass1!', cpwd: 'Pass1!', expected: false },
  { name: 'spaces only', pwd: '        ', cpwd: '        ', expected: false },
  { name: 'space instead of special character', pwd: 'Password 123', cpwd: 'Password 123', expected: false },
  { name: 'password confirmation mismatch', pwd: 'Password123!', cpwd: 'Password123?', expectedMismatch: true }
];

let failed = 0;
console.log('--- RUNNING PASSWORD POLICY TESTS ---');
for (const tc of testCases) {
  const res = checkPasswordPolicy(tc.pwd, tc.cpwd);
  if (tc.expectedMismatch) {
    if (res.passwordsMatch) {
      console.error(`FAIL: ${tc.name} expected mismatch but matched`);
      failed++;
    } else {
      console.log(`PASS: ${tc.name} (correctly detected mismatch)`);
    }
    continue;
  }
  if (res.isValid !== tc.expected) {
    console.error(`FAIL: ${tc.name} (pwd="${tc.pwd}") expected ${tc.expected}, got ${res.isValid}`, res);
    failed++;
  } else {
    console.log(`PASS: ${tc.name} -> valid=${res.isValid}`);
  }
}

if (failed === 0) {
  console.log(`ALL ${testCases.length} PASSWORD POLICY TESTS PASSED!`);
} else {
  console.error(`${failed} tests failed!`);
  process.exit(1);
}
