import 'dart:convert';
import 'dart:math';

import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';
import 'package:flutter/services.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:http/http.dart' as http;

const _blue = Color(0xFF2457D6);
const _ink = Color(0xFF14213D);
const _muted = Color(0xFF7E8AA3);
const _canvas = Color(0xFFF4F7FC);

String _newIdempotencyKey() {
  final random = Random.secure();
  final bytes = List<int>.generate(16, (_) => random.nextInt(256));
  final hex = bytes
      .map((byte) => byte.toRadixString(16).padLeft(2, '0'))
      .join();
  return '${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}';
}

void main() {
  runApp(const ProviderScope(child: CobaltoRoot()));
}

final apiClientProvider = Provider<ApiClient>((ref) => ApiClient());

class CobaltoRoot extends ConsumerWidget {
  const CobaltoRoot({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) =>
      CobaltoApp(api: ref.watch(apiClientProvider));
}

class CobaltoApp extends StatelessWidget {
  CobaltoApp({required this.api, super.key})
    : _router = GoRouter(
        routes: [
          GoRoute(
            path: '/',
            builder: (context, state) => SessionGate(api: api),
          ),
          GoRoute(
            path: '/verify-email',
            builder: (context, state) => EmailVerificationScreen(
              api: api,
              token: state.uri.fragment.startsWith('token=')
                  ? state.uri.fragment.substring('token='.length)
                  : '',
            ),
          ),
        ],
      );

  final ApiClient api;
  final GoRouter _router;

  @override
  Widget build(BuildContext context) {
    return MaterialApp.router(
      title: 'Cobalto',
      debugShowCheckedModeBanner: false,
      routerConfig: _router,
      theme: ThemeData(
        useMaterial3: true,
        colorScheme: ColorScheme.fromSeed(
          seedColor: _blue,
          primary: _blue,
          surface: Colors.white,
        ),
        scaffoldBackgroundColor: _canvas,
        fontFamily: 'Roboto',
        inputDecorationTheme: InputDecorationTheme(
          filled: true,
          fillColor: const Color(0xFFF5F7FB),
          border: OutlineInputBorder(
            borderRadius: BorderRadius.circular(14),
            borderSide: BorderSide.none,
          ),
          contentPadding: const EdgeInsets.symmetric(
            horizontal: 16,
            vertical: 16,
          ),
        ),
      ),
    );
  }
}

class ApiClient {
  ApiClient({http.Client? httpClient, AuthTokenStorage? tokenStorage})
    : _baseUri = Uri.parse(_apiBaseUrl),
      _http = httpClient ?? http.Client(),
      _tokenStorage = tokenStorage ?? const FlutterAuthTokenStorage() {
    if (kReleaseMode &&
        (_baseUri.scheme != 'https' ||
            _baseUri.host.isEmpty ||
            _isLoopbackHost(_baseUri.host))) {
      throw StateError(
        'La versión de producción requiere una API HTTPS pública.',
      );
    }
  }

  static bool _isLoopbackHost(String host) {
    final normalizedHost = host.toLowerCase().replaceFirst(RegExp(r'\.$'), '');
    return normalizedHost == 'localhost' ||
        normalizedHost.endsWith('.localhost') ||
        normalizedHost.startsWith('127.') ||
        normalizedHost == '::1' ||
        normalizedHost == '[::1]' ||
        normalizedHost == '0.0.0.0' ||
        normalizedHost == '[::]';
  }

  static const _apiBaseUrl = String.fromEnvironment(
    'API_BASE_URL',
    defaultValue: 'http://localhost:8080/api',
  );
  static const _accessTokenKey = 'cobalto_access_token';
  static const _refreshTokenKey = 'cobalto_refresh_token';
  final Uri _baseUri;
  final http.Client _http;
  final AuthTokenStorage _tokenStorage;
  String? _token;
  String? _refreshToken;
  Future<bool>? _refreshInProgress;

  Future<AuthUser?> restoreSession() async {
    _token = await _tokenStorage.read(_accessTokenKey);
    _refreshToken = await _tokenStorage.read(_refreshTokenKey);
    if (_token == null && (_refreshToken == null || !await _refreshSession())) {
      return null;
    }
    try {
      return AuthUser.fromJson(await _request('GET', '/me'));
    } on ApiException catch (exception) {
      if (exception.statusCode == 401 || exception.statusCode == 403) {
        await signOut();
      }
      rethrow;
    }
  }

  Future<AuthUser> register(String name, String email, String password) async {
    return _authenticate('POST', '/auth/register', {
      'fullName': name.trim(),
      'email': email.trim(),
      'password': password,
    });
  }

  Future<AuthUser> login(String email, String password) async {
    final response = await _request(
      'POST',
      '/auth/login',
      body: {'email': email.trim(), 'password': password},
    );
    if (response['mfaRequired'] == true) {
      final challenge = response['mfaChallenge'];
      if (challenge is! String) {
        throw const FormatException('Respuesta MFA inválida');
      }
      throw MfaRequiredException(challenge);
    }
    return _saveAuthenticatedSession(response);
  }

  Future<void> confirmEmailVerification(String token) async {
    await _request(
      'POST',
      '/auth/email-verification/confirm',
      body: {'token': token},
    );
  }

  Future<bool> resendEmailVerification() async {
    final response = await _request('POST', '/auth/email-verification/resend');
    final emailVerified = response['emailVerified'];
    if (emailVerified is! bool) {
      throw const FormatException(
        'Respuesta de verificación de correo inválida',
      );
    }
    return emailVerified;
  }

  Future<AuthUser> loadCurrentUser() async =>
      AuthUser.fromJson(await _request('GET', '/me'));

  Future<AuthUser> _authenticate(
    String method,
    String path,
    Map<String, Object> body,
  ) async {
    final response = await _request(method, path, body: body);
    return _saveAuthenticatedSession(response);
  }

  Future<AuthUser> verifyMfaLogin(String challenge, String factor) async {
    final response = await _request(
      'POST',
      '/auth/mfa/verify',
      body: {
        'challenge': challenge,
        if (RegExp(r'^\d{6}$').hasMatch(factor.trim()))
          'code': factor.trim()
        else
          'recoveryCode': factor.trim(),
      },
    );
    return _saveAuthenticatedSession(response);
  }

  Future<Map<String, dynamic>> beginMfaEnrollment(String password) =>
      _request('POST', '/auth/mfa/enrollment', body: {'password': password});

  Future<Map<String, dynamic>> confirmMfaEnrollment(String code) async {
    final response = await _request(
      'POST',
      '/auth/mfa/enrollment/confirm',
      body: {'code': code},
    );
    final session = response['session'];
    if (session is! Map<String, dynamic>) {
      throw const FormatException('Respuesta de sesión MFA inválida');
    }
    await _saveAuthenticatedSession(session);
    return response;
  }

  Future<void> disableMfa(String password, String factor) async {
    final body = <String, Object>{'password': password};
    if (RegExp(r'^\d{6}$').hasMatch(factor.trim())) {
      body['code'] = factor.trim();
    } else {
      body['recoveryCode'] = factor.trim();
    }
    await _request('DELETE', '/auth/mfa/enrollment', body: body);
  }

  Future<AuthUser> _saveAuthenticatedSession(
    Map<String, dynamic> response,
  ) async {
    final accessToken = response['accessToken'];
    final refreshToken = response['refreshToken'];
    final userData = response['user'];
    if (accessToken is! String ||
        refreshToken is! String ||
        userData is! Map<String, dynamic>) {
      throw const FormatException('Respuesta de autenticación inválida');
    }
    await _tokenStorage.write(_accessTokenKey, accessToken);
    await _tokenStorage.write(_refreshTokenKey, refreshToken);
    _token = accessToken;
    _refreshToken = refreshToken;
    return AuthUser.fromJson(userData);
  }

  Future<WalletSnapshot> loadWallet() async {
    final response = await _request('GET', '/wallet');
    return WalletSnapshot.fromJson(response);
  }

  Future<List<Movement>> loadMovements({int limit = 50, int offset = 0}) async {
    final response = await _request(
      'GET',
      '/wallet/movements?limit=$limit&offset=$offset',
    );
    final movements = response['items'];
    if (movements is List<dynamic>) {
      return movements
          .map((item) => Movement.fromJson(item as Map<String, dynamic>))
          .toList();
    }
    throw const FormatException('Respuesta de movimientos inválida');
  }

  Future<RecipientConfirmation> confirmRecipient(String email) async {
    final response = await _request(
      'GET',
      '/recipients/confirm?email=${Uri.encodeQueryComponent(email.trim())}',
    );
    return RecipientConfirmation.fromJson(response);
  }

  Future<TransferReceipt> transfer(
    String recipientEmail,
    String amount,
    String password,
    String? mfaFactor,
    String idempotencyKey,
  ) async {
    if (!RegExp(r'^(0|[1-9]\d{0,14})(\.\d{1,2})?$').hasMatch(amount)) {
      throw ArgumentError.value(amount, 'amount', 'Monto decimal inválido');
    }
    final response = await _request(
      'POST',
      '/transfers',
      rawBody:
          '{"recipientEmail":${jsonEncode(recipientEmail.trim())},'
          '"amount":$amount,"password":${jsonEncode(password)}'
          '${mfaFactor == null ? '' : ',"mfaFactor":${jsonEncode(mfaFactor)}'}}',
      headers: {'Idempotency-Key': idempotencyKey},
    );
    return TransferReceipt.fromJson(response);
  }

  Future<TransferReceipt> loadTransferDetails(String transactionId) async {
    final response = await _request('GET', '/transfers/$transactionId');
    return TransferReceipt.fromJson(response);
  }

  Future<TransferReceipt> reverseTransfer(
    String transactionId,
    String reason,
    String password,
    String? mfaFactor,
    String idempotencyKey,
  ) async {
    final body = <String, Object>{
      'reason': reason.trim(),
      'password': password,
    };
    if (mfaFactor != null) {
      body['mfaFactor'] = mfaFactor;
    }
    final response = await _request(
      'POST',
      '/transfers/$transactionId/reversal',
      body: body,
      headers: {'Idempotency-Key': idempotencyKey},
    );
    return TransferReceipt.fromJson(response);
  }

  Future<List<AppNotification>> loadNotifications() async {
    final response = await _request('GET', '/notifications');
    final notifications = response['items'];
    if (notifications is List<dynamic>) {
      return notifications
          .map((item) => AppNotification.fromJson(item as Map<String, dynamic>))
          .toList();
    }
    throw const FormatException('Respuesta de notificaciones inválida');
  }

  Future<void> markNotificationRead(String notificationId) async {
    await _request('POST', '/notifications/$notificationId/read');
  }

  Future<Map<String, dynamic>> _request(
    String method,
    String path, {
    Map<String, Object>? body,
    String? rawBody,
    Map<String, String> headers = const {},
  }) async {
    var response = await _send(
      method,
      path,
      body: body,
      rawBody: rawBody,
      headers: headers,
    );
    if (response.statusCode == 401 &&
        _token != null &&
        !_requiresFreshLogin(path) &&
        await _refreshSession()) {
      response = await _send(
        method,
        path,
        body: body,
        rawBody: rawBody,
        headers: headers,
      );
    }
    _checkResponse(response);
    if (response.body.isEmpty) return {};
    final decoded = jsonDecode(response.body);
    if (decoded is! Map<String, dynamic>) {
      throw const FormatException('Respuesta API inválida');
    }
    return decoded;
  }

  Future<http.Response> _send(
    String method,
    String path, {
    Map<String, Object>? body,
    String? rawBody,
    Map<String, String> headers = const {},
    bool includeAuthorization = true,
  }) async {
    final request = http.Request(method, _uri(path));
    request.headers.addAll({
      'Content-Type': 'application/json',
      if (includeAuthorization && _token != null)
        'Authorization': 'Bearer $_token',
      ...headers,
    });
    request.body = rawBody ?? (body == null ? '' : jsonEncode(body));
    return http.Response.fromStream(await _http.send(request));
  }

  Future<bool> _refreshSession() async {
    final existingRefresh = _refreshInProgress;
    if (existingRefresh != null) return existingRefresh;

    final refreshToken =
        _refreshToken ?? await _tokenStorage.read(_refreshTokenKey);
    if (refreshToken == null) return false;
    final refresh = _performRefresh(refreshToken);
    _refreshInProgress = refresh;
    try {
      return await refresh;
    } finally {
      if (identical(_refreshInProgress, refresh)) _refreshInProgress = null;
    }
  }

  Future<bool> _performRefresh(String refreshToken) async {
    final response = await _send(
      'POST',
      '/auth/refresh',
      body: {'refreshToken': refreshToken},
      includeAuthorization: false,
    );
    if (response.statusCode == 401 || response.statusCode == 403) {
      await _clearTokens();
      return false;
    }
    _checkResponse(response);
    final decoded = jsonDecode(response.body);
    if (decoded is! Map<String, dynamic>) {
      throw const FormatException('Respuesta de renovación inválida');
    }
    await _saveAuthenticatedSession(decoded);
    return true;
  }

  bool _requiresFreshLogin(String path) => const {
    '/auth/register',
    '/auth/login',
    '/auth/refresh',
    '/auth/logout',
    '/auth/mfa/verify',
    '/auth/email-verification/confirm',
  }.contains(path);

  Future<void> _clearTokens() async {
    _token = null;
    _refreshToken = null;
    await _tokenStorage.delete(_accessTokenKey);
    await _tokenStorage.delete(_refreshTokenKey);
  }

  void _checkResponse(http.Response response) {
    if (response.statusCode >= 200 && response.statusCode < 300) return;
    String message = 'No se pudo completar la solicitud. Intenta de nuevo.';
    try {
      final body = jsonDecode(response.body);
      if (body is Map<String, dynamic> && body['message'] is String) {
        message = body['message'] as String;
      }
    } on FormatException {
      // Use the generic message when the server does not return a JSON API error.
    }
    throw ApiException(message, response.statusCode);
  }

  Uri _uri(String path) {
    final root = _baseUri.toString().replaceFirst(RegExp(r'/$'), '');
    final segment = path.startsWith('/') ? path.substring(1) : path;
    return Uri.parse('$root/$segment');
  }

  Future<void> signOut() async {
    final refreshToken =
        _refreshToken ?? await _tokenStorage.read(_refreshTokenKey);
    try {
      if (refreshToken != null) {
        final response = await _send(
          'POST',
          '/auth/logout',
          body: {'refreshToken': refreshToken},
          includeAuthorization: false,
        );
        _checkResponse(response);
      }
    } finally {
      await _clearTokens();
    }
  }
}

abstract interface class AuthTokenStorage {
  Future<String?> read(String key);
  Future<void> write(String key, String value);
  Future<void> delete(String key);
}

class FlutterAuthTokenStorage implements AuthTokenStorage {
  const FlutterAuthTokenStorage([this._storage = const FlutterSecureStorage()]);

  final FlutterSecureStorage _storage;

  @override
  Future<String?> read(String key) => _storage.read(key: key);

  @override
  Future<void> write(String key, String value) =>
      _storage.write(key: key, value: value);

  @override
  Future<void> delete(String key) => _storage.delete(key: key);
}

class MfaRequiredException implements Exception {
  const MfaRequiredException(this.challenge);

  final String challenge;
}

class ApiException implements Exception {
  const ApiException(this.message, this.statusCode);

  final String message;
  final int statusCode;
}

class AuthUser {
  const AuthUser({
    required this.id,
    required this.fullName,
    required this.email,
    required this.kycStatus,
    required this.emailVerified,
    required this.mfaEnabled,
    this.canReverseTransfers = false,
  });

  final String id;
  final String fullName;
  final String email;
  final String kycStatus;
  final bool emailVerified;
  final bool mfaEnabled;
  final bool canReverseTransfers;

  factory AuthUser.fromJson(Map<String, dynamic> json) => AuthUser(
    id: json['id'] as String,
    fullName: json['fullName'] as String,
    email: json['email'] as String,
    kycStatus: json['kycStatus'] as String? ?? 'NOT_VERIFIED',
    emailVerified: json['emailVerified'] as bool? ?? false,
    mfaEnabled: json['mfaEnabled'] as bool? ?? false,
    canReverseTransfers: json['canReverseTransfers'] as bool? ?? false,
  );

  AuthUser withMfaEnabled(bool enabled) => AuthUser(
    id: id,
    fullName: fullName,
    email: email,
    kycStatus: kycStatus,
    emailVerified: emailVerified,
    mfaEnabled: enabled,
    canReverseTransfers: canReverseTransfers,
  );

  AuthUser withEmailVerified(bool verified) => AuthUser(
    id: id,
    fullName: fullName,
    email: email,
    kycStatus: kycStatus,
    emailVerified: verified,
    mfaEnabled: mfaEnabled,
    canReverseTransfers: canReverseTransfers,
  );
}

class WalletSnapshot {
  const WalletSnapshot({
    required this.currency,
    required this.balance,
    required this.availableBalance,
    required this.heldBalance,
    required this.movements,
  });

  final String currency;
  final num balance;
  final num availableBalance;
  final num heldBalance;
  final List<Movement> movements;

  factory WalletSnapshot.fromJson(Map<String, dynamic> json) => WalletSnapshot(
    currency: json['currency'] as String,
    balance: json['balance'] as num,
    availableBalance:
        json['availableBalance'] as num? ?? json['balance'] as num,
    heldBalance: json['heldBalance'] as num? ?? 0,
    movements: (json['movements'] as List<dynamic>)
        .map((item) => Movement.fromJson(item as Map<String, dynamic>))
        .toList(),
  );
}

class RecipientConfirmation {
  const RecipientConfirmation({
    required this.fullName,
    required this.maskedEmail,
  });

  final String fullName;
  final String maskedEmail;

  factory RecipientConfirmation.fromJson(Map<String, dynamic> json) =>
      RecipientConfirmation(
        fullName: json['fullName'] as String,
        maskedEmail: json['maskedEmail'] as String,
      );
}

class Movement {
  const Movement({
    required this.transactionId,
    required this.type,
    required this.description,
    required this.createdAt,
    required this.amount,
    required this.status,
  });

  final String transactionId;
  final String type;
  final String description;
  final DateTime createdAt;
  final num amount;
  final String status;

  factory Movement.fromJson(Map<String, dynamic> json) => Movement(
    transactionId: json['transactionId'] as String,
    type: json['type'] as String,
    description: json['description'] as String,
    createdAt: DateTime.parse(json['createdAt'] as String),
    amount: json['amount'] as num,
    status: json['status'] as String? ?? 'COMPLETED',
  );
}

class TransferReceipt {
  const TransferReceipt({
    required this.transactionId,
    required this.receiptNumber,
    required this.status,
    required this.direction,
    required this.currency,
    required this.amount,
    required this.createdAt,
    required this.description,
    required this.counterpartyName,
    required this.maskedCounterpartyEmail,
    this.operationType = 'TRANSFER',
    this.originalTransactionId,
    this.reason,
  });

  final String transactionId;
  final String receiptNumber;
  final String status;
  final String direction;
  final String currency;
  final num amount;
  final DateTime createdAt;
  final String description;
  final String counterpartyName;
  final String maskedCounterpartyEmail;
  final String operationType;
  final String? originalTransactionId;
  final String? reason;

  factory TransferReceipt.fromJson(Map<String, dynamic> json) =>
      TransferReceipt(
        transactionId: json['transactionId'] as String,
        receiptNumber: json['receiptNumber'] as String,
        status: json['status'] as String,
        direction: json['direction'] as String,
        currency: json['currency'] as String,
        amount: json['amount'] as num,
        createdAt: DateTime.parse(json['createdAt'] as String),
        description: json['description'] as String,
        counterpartyName: json['counterpartyName'] as String,
        maskedCounterpartyEmail: json['maskedCounterpartyEmail'] as String,
        operationType: json['operationType'] as String? ?? 'TRANSFER',
        originalTransactionId: json['originalTransactionId'] as String?,
        reason: json['reason'] as String?,
      );
}

class AppNotification {
  const AppNotification({
    required this.id,
    required this.transactionId,
    required this.type,
    required this.message,
    required this.createdAt,
    required this.readAt,
  });

  final String id;
  final String transactionId;
  final String type;
  final String message;
  final DateTime createdAt;
  final DateTime? readAt;

  factory AppNotification.fromJson(Map<String, dynamic> json) =>
      AppNotification(
        id: json['id'] as String,
        transactionId: json['transactionId'] as String,
        type: json['type'] as String,
        message: json['message'] as String,
        createdAt: DateTime.parse(json['createdAt'] as String),
        readAt: json['readAt'] == null
            ? null
            : DateTime.parse(json['readAt'] as String),
      );
}

class SessionGate extends StatefulWidget {
  const SessionGate({required this.api, super.key});

  final ApiClient api;

  @override
  State<SessionGate> createState() => _SessionGateState();
}

class _SessionGateState extends State<SessionGate> {
  AuthUser? _user;
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _restore();
  }

  Future<void> _restore() async {
    try {
      final user = await widget.api.restoreSession();
      if (mounted) setState(() => _user = user);
    } on Exception {
      if (mounted) setState(() => _user = null);
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_loading) {
      return const Scaffold(body: Center(child: CircularProgressIndicator()));
    }
    if (_user == null) {
      return AuthScreen(
        api: widget.api,
        onAuthenticated: (user) => setState(() => _user = user),
      );
    }
    return HomeScreen(
      api: widget.api,
      user: _user!,
      onUserUpdated: (user) => setState(() => _user = user),
      onSignOut: () async {
        try {
          await widget.api.signOut();
        } finally {
          if (mounted) setState(() => _user = null);
        }
      },
    );
  }
}

class EmailVerificationScreen extends StatefulWidget {
  const EmailVerificationScreen({
    required this.api,
    required this.token,
    super.key,
  });

  final ApiClient api;
  final String token;

  @override
  State<EmailVerificationScreen> createState() =>
      _EmailVerificationScreenState();
}

class _EmailVerificationScreenState extends State<EmailVerificationScreen> {
  bool _loading = true;
  bool _verified = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _confirm();
  }

  Future<void> _confirm() async {
    if (widget.token.isEmpty) {
      setState(() {
        _loading = false;
        _error = 'El enlace de verificación no contiene un token válido.';
      });
      return;
    }
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      await widget.api.confirmEmailVerification(widget.token);
      if (mounted) setState(() => _verified = true);
    } on ApiException catch (exception) {
      if (mounted) setState(() => _error = exception.message);
    } on Exception {
      if (mounted) {
        setState(
          () => _error = 'No se pudo verificar el correo. Inténtalo de nuevo.',
        );
      }
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    body: SafeArea(
      child: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 430),
          child: Padding(
            padding: const EdgeInsets.all(24),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                const _BrandMark(),
                const SizedBox(height: 24),
                Text(
                  _loading
                      ? 'Verificando tu correo…'
                      : _verified
                      ? 'Correo verificado'
                      : 'No se pudo verificar',
                  textAlign: TextAlign.center,
                  style: const TextStyle(
                    color: _ink,
                    fontSize: 22,
                    fontWeight: FontWeight.w700,
                  ),
                ),
                const SizedBox(height: 12),
                if (_loading)
                  const Center(child: CircularProgressIndicator())
                else
                  Text(
                    _verified
                        ? 'Tu dirección ya puede recibir transferencias Sandbox.'
                        : _error ?? 'El enlace ya no es válido.',
                    textAlign: TextAlign.center,
                    style: const TextStyle(color: _muted),
                  ),
                const SizedBox(height: 20),
                if (!_loading && !_verified && widget.token.isNotEmpty)
                  OutlinedButton(
                    onPressed: _confirm,
                    child: const Text('Intentar de nuevo'),
                  ),
                if (!_loading)
                  FilledButton(
                    onPressed: () => context.go('/'),
                    child: const Text('Volver a Cobalto'),
                  ),
              ],
            ),
          ),
        ),
      ),
    ),
  );
}

class AuthScreen extends StatefulWidget {
  const AuthScreen({
    required this.api,
    required this.onAuthenticated,
    super.key,
  });

  final ApiClient api;
  final ValueChanged<AuthUser> onAuthenticated;

  @override
  State<AuthScreen> createState() => _AuthScreenState();
}

class _AuthScreenState extends State<AuthScreen> {
  final _formKey = GlobalKey<FormState>();
  final _name = TextEditingController();
  final _email = TextEditingController();
  final _password = TextEditingController();
  bool _registering = false;
  bool _busy = false;
  bool _obscurePassword = true;
  String? _error;

  @override
  void dispose() {
    _name.dispose();
    _email.dispose();
    _password.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final user = _registering
          ? await widget.api.register(_name.text, _email.text, _password.text)
          : await widget.api.login(_email.text, _password.text);
      if (mounted) widget.onAuthenticated(user);
    } on MfaRequiredException catch (exception) {
      if (!mounted) return;
      final factor = await showDialog<String>(
        context: context,
        builder: (context) => const _MfaCodeDialog(
          title: 'Verificación en dos pasos',
          description: 'Ingresa el código de tu app autenticadora o uno de recuperación.',
        ),
      );
      if (!mounted || factor == null) return;
      try {
        final user = await widget.api.verifyMfaLogin(
          exception.challenge,
          factor,
        );
        if (mounted) widget.onAuthenticated(user);
      } on ApiException catch (apiException) {
        if (mounted) setState(() => _error = apiException.message);
      } on Exception {
        if (mounted) {
          setState(() => _error = 'No se pudo completar la verificación MFA.');
        }
      }
    } on ApiException catch (exception) {
      if (mounted) setState(() => _error = exception.message);
    } on Exception {
      if (mounted) {
        setState(
          () => _error = 'No hay conexión con Cobalto. Revisa la API e inténtalo de nuevo.',
        );
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: SafeArea(
        child: Center(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(24),
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 430),
              child: Card(
                elevation: 0,
                color: Colors.white,
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(28),
                ),
                child: Padding(
                  padding: const EdgeInsets.all(30),
                  child: Form(
                    key: _formKey,
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        const _BrandMark(),
                        const SizedBox(height: 28),
                        Text(
                          _registering
                              ? 'Tu dinero, más cerca.'
                              : 'Qué gusto verte.',
                          style: const TextStyle(
                            color: _ink,
                            fontSize: 27,
                            fontWeight: FontWeight.w700,
                            letterSpacing: -0.6,
                          ),
                        ),
                        const SizedBox(height: 8),
                        Text(
                          _registering
                              ? 'Crea tu cuenta Cobalto para empezar.'
                              : 'Ingresa a tu billetera Cobalto.',
                          style: const TextStyle(color: _muted, fontSize: 15),
                        ),
                        const SizedBox(height: 28),
                        if (_registering) ...[
                          TextFormField(
                            controller: _name,
                            textCapitalization: TextCapitalization.words,
                            decoration: const InputDecoration(
                              labelText: 'Nombre completo',
                            ),
                            validator: (value) =>
                                (value == null || value.trim().length < 2)
                                ? 'Ingresa tu nombre'
                                : null,
                          ),
                          const SizedBox(height: 14),
                        ],
                        TextFormField(
                          controller: _email,
                          keyboardType: TextInputType.emailAddress,
                          autocorrect: false,
                          decoration: const InputDecoration(
                            labelText: 'Correo electrónico',
                          ),
                          validator: (value) {
                            final email = value?.trim() ?? '';
                            return RegExp(r'^[^@\s]+@[^@\s]+\.[^@\s]+$')
                                    .hasMatch(email)
                                ? null
                                : 'Ingresa un correo válido';
                          },
                        ),
                        const SizedBox(height: 14),
                        TextFormField(
                          controller: _password,
                          obscureText: _obscurePassword,
                          decoration: InputDecoration(
                            labelText: 'Contraseña',
                            suffixIcon: IconButton(
                              onPressed: () => setState(
                                () => _obscurePassword = !_obscurePassword,
                              ),
                              icon: Icon(
                                _obscurePassword
                                    ? Icons.visibility_outlined
                                    : Icons.visibility_off_outlined,
                              ),
                            ),
                          ),
                          validator: (value) => (value?.length ?? 0) < 12
                              ? 'Usa al menos 12 caracteres'
                              : null,
                        ),
                        if (_error != null) ...[
                          const SizedBox(height: 14),
                          Text(
                            _error!,
                            style: const TextStyle(color: Color(0xFFB3261E)),
                          ),
                        ],
                        const SizedBox(height: 22),
                        FilledButton(
                          onPressed: _busy ? null : _submit,
                          style: FilledButton.styleFrom(
                            minimumSize: const Size.fromHeight(54),
                            shape: RoundedRectangleBorder(
                              borderRadius: BorderRadius.circular(15),
                            ),
                          ),
                          child: _busy
                              ? const SizedBox(
                                  width: 21,
                                  height: 21,
                                  child: CircularProgressIndicator(
                                    strokeWidth: 2,
                                  ),
                                )
                              : Text(
                                  _registering
                                      ? 'Crear cuenta'
                                      : 'Iniciar sesión',
                                ),
                        ),
                        const SizedBox(height: 12),
                        TextButton(
                          onPressed: _busy
                              ? null
                              : () => setState(() {
                                  _registering = !_registering;
                                  _error = null;
                                }),
                          child: Text(
                            _registering
                                ? 'Ya tengo una cuenta · Iniciar sesión'
                                : '¿Primera vez en Cobalto? Crear cuenta',
                          ),
                        ),
                        const SizedBox(height: 12),
                        const Text(
                          'Entorno sandbox · No se mueve dinero real',
                          textAlign: TextAlign.center,
                          style: TextStyle(color: _muted, fontSize: 12),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _MfaCodeDialog extends StatefulWidget {
  const _MfaCodeDialog({required this.title, required this.description});

  final String title;
  final String description;

  @override
  State<_MfaCodeDialog> createState() => _MfaCodeDialogState();
}

class _MfaCodeDialogState extends State<_MfaCodeDialog> {
  final _formKey = GlobalKey<FormState>();
  final _code = TextEditingController();

  @override
  void dispose() {
    _code.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
    title: Text(widget.title),
    content: Form(
      key: _formKey,
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(widget.description),
          const SizedBox(height: 16),
          TextFormField(
            controller: _code,
            autofocus: true,
            autocorrect: false,
            decoration: const InputDecoration(labelText: 'Código'),
            validator: (value) {
              final code = value?.trim() ?? '';
              if (RegExp(r'^\d{6}$').hasMatch(code) ||
                  RegExp(r'^(?:[0-9a-fA-F]{4}-?){7}[0-9a-fA-F]{4}$')
                      .hasMatch(code)) {
                return null;
              }
              return 'Ingresa un código de 6 dígitos o de recuperación';
            },
          ),
        ],
      ),
    ),
    actions: [
      TextButton(
        onPressed: () => Navigator.pop(context),
        child: const Text('Cancelar'),
      ),
      FilledButton(
        onPressed: () {
          if (_formKey.currentState!.validate()) {
            Navigator.pop(context, _code.text.trim());
          }
        },
        child: const Text('Verificar'),
      ),
    ],
  );
}

class HomeScreen extends StatefulWidget {
  const HomeScreen({
    required this.api,
    required this.user,
    required this.onUserUpdated,
    required this.onSignOut,
    super.key,
  });

  final ApiClient api;
  final AuthUser user;
  final ValueChanged<AuthUser> onUserUpdated;
  final Future<void> Function() onSignOut;

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  WalletSnapshot? _wallet;
  bool _loading = true;
  bool _showBalance = true;
  bool _verificationBusy = false;
  String? _verificationMessage;
  String? _verificationError;
  String? _error;

  @override
  void initState() {
    super.initState();
    _refresh();
  }

  Future<void> _refresh() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final wallet = await widget.api.loadWallet();
      if (mounted) setState(() => _wallet = wallet);
    } on ApiException catch (exception) {
      if (mounted) setState(() => _error = exception.message);
    } on Exception {
      if (mounted) {
        setState(() => _error = 'No se pudo actualizar la billetera.');
      }
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  Future<void> _resendVerification() async {
    setState(() {
      _verificationBusy = true;
      _verificationMessage = null;
      _verificationError = null;
    });
    try {
      final verified = await widget.api.resendEmailVerification();
      if (!mounted) return;
      if (verified) {
        widget.onUserUpdated(widget.user.withEmailVerified(true));
        setState(() => _verificationMessage = 'Tu correo ya está verificado.');
      } else {
        setState(
          () => _verificationMessage =
              'Si tu cuenta requiere verificación, enviaremos un correo.',
        );
      }
    } on ApiException catch (exception) {
      if (mounted) setState(() => _verificationError = exception.message);
    } on Exception {
      if (mounted) {
        setState(() => _verificationError = 'No se pudo reenviar el correo.');
      }
    } finally {
      if (mounted) setState(() => _verificationBusy = false);
    }
  }

  Future<void> _refreshVerificationStatus() async {
    setState(() {
      _verificationBusy = true;
      _verificationMessage = null;
      _verificationError = null;
    });
    try {
      final user = await widget.api.loadCurrentUser();
      if (mounted) {
        widget.onUserUpdated(user);
        setState(
          () => _verificationMessage = user.emailVerified
              ? 'Tu correo ya está verificado.'
              : 'Aún falta verificar tu correo.',
        );
      }
    } on ApiException catch (exception) {
      if (mounted) setState(() => _verificationError = exception.message);
    } on Exception {
      if (mounted) {
        setState(() => _verificationError = 'No se pudo actualizar el estado.');
      }
    } finally {
      if (mounted) setState(() => _verificationBusy = false);
    }
  }

  Future<void> _openTransfer() async {
    final receipt = await showDialog<TransferReceipt>(
      context: context,
      builder: (context) =>
          TransferDialog(api: widget.api, mfaEnabled: widget.user.mfaEnabled),
    );
    if (receipt != null) {
      await _refresh();
      if (mounted) {
        await _showReceipt(receipt);
      }
    }
  }

  Future<void> _showReceipt(TransferReceipt initialReceipt) async {
    var receipt = initialReceipt;
    while (true) {
      if (!mounted) return;
      final nextReceipt = await showModalBottomSheet<TransferReceipt>(
        context: context,
        isScrollControlled: true,
        showDragHandle: true,
        builder: (context) => TransferReceiptSheet(
          receipt: receipt,
          api: widget.api,
          canReverseTransfers: widget.user.canReverseTransfers,
          mfaEnabled: widget.user.mfaEnabled,
        ),
      );
      if (!mounted || nextReceipt == null) return;
      await _refresh();
      receipt = nextReceipt;
    }
  }

  Future<void> _openHistory() => showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    showDragHandle: true,
    builder: (context) =>
        MovementHistorySheet(api: widget.api, onSelect: _openReceipt),
  );

  Future<void> _openNotifications() => showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    showDragHandle: true,
    builder: (context) =>
        NotificationsSheet(api: widget.api, onSelectTransfer: _openReceiptById),
  );

  Future<void> _openReceipt(Movement movement) =>
      _openReceiptById(movement.transactionId);

  Future<void> _openReceiptById(String transactionId) async {
    try {
      final receipt = await widget.api.loadTransferDetails(transactionId);
      if (!mounted) return;
      await _showReceipt(receipt);
    } on ApiException catch (exception) {
      if (mounted) _showMessage(exception.message);
    } on Exception {
      if (mounted) _showMessage('No se pudo cargar el comprobante.');
    }
  }

  void _receive() {
    Clipboard.setData(ClipboardData(text: widget.user.email));
    _showMessage('Tu correo para recibir dinero se copió');
  }

  void _showMessage(String message) {
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(
        SnackBar(content: Text(message), behavior: SnackBarBehavior.floating),
      );
  }

  @override
  Widget build(BuildContext context) {
    final firstName = widget.user.fullName.split(' ').first;
    return Scaffold(
      appBar: AppBar(
        backgroundColor: _canvas,
        surfaceTintColor: Colors.transparent,
        titleSpacing: 22,
        title: const _BrandMark(compact: true),
        actions: [
          IconButton(
            tooltip: 'Notificaciones',
            onPressed: _openNotifications,
            icon: const Icon(Icons.notifications_none_rounded, color: _ink),
          ),
          IconButton(
            tooltip: 'Perfil',
            onPressed: () => showModalBottomSheet<void>(
              context: context,
              showDragHandle: true,
              builder: (context) => _ProfileSheet(
                api: widget.api,
                user: widget.user,
                onUserUpdated: widget.onUserUpdated,
                onSignOut: widget.onSignOut,
              ),
            ),
            icon: const Icon(Icons.account_circle_outlined, color: _ink),
          ),
          const SizedBox(width: 14),
        ],
      ),
      body: RefreshIndicator(
        onRefresh: _refresh,
        child: Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 760),
            child: ListView(
              physics: const AlwaysScrollableScrollPhysics(),
              padding: const EdgeInsets.fromLTRB(22, 16, 22, 32),
              children: [
                Text(
                  'Hola, $firstName',
                  style: const TextStyle(
                    color: _ink,
                    fontSize: 25,
                    fontWeight: FontWeight.w700,
                  ),
                ),
                const SizedBox(height: 5),
                const Text(
                  'Tu dinero, claro y a tu ritmo.',
                  style: TextStyle(color: _muted),
                ),
                if (!widget.user.emailVerified) ...[
                  const SizedBox(height: 16),
                  Card(
                    color: const Color(0xFFFFF7E5),
                    elevation: 0,
                    child: Padding(
                      padding: const EdgeInsets.all(14),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          const Text(
                            'Verifica tu correo para recibir transferencias.',
                            style: TextStyle(
                              color: _ink,
                              fontWeight: FontWeight.w700,
                            ),
                          ),
                          const SizedBox(height: 8),
                          if (_verificationMessage != null)
                            Text(
                              _verificationMessage!,
                              style: const TextStyle(color: _muted),
                            ),
                          if (_verificationError != null)
                            Text(
                              _verificationError!,
                              style: const TextStyle(color: Color(0xFFB3261E)),
                            ),
                          Wrap(
                            spacing: 8,
                            children: [
                              TextButton(
                                onPressed: _verificationBusy
                                    ? null
                                    : _resendVerification,
                                child: _verificationBusy
                                    ? const SizedBox(
                                        width: 18,
                                        height: 18,
                                        child: CircularProgressIndicator(
                                          strokeWidth: 2,
                                        ),
                                      )
                                    : const Text('Reenviar correo'),
                              ),
                              TextButton(
                                onPressed: _verificationBusy
                                    ? null
                                    : _refreshVerificationStatus,
                                child: const Text('Actualizar estado'),
                              ),
                            ],
                          ),
                        ],
                      ),
                    ),
                  ),
                ],
                const SizedBox(height: 22),
                _WalletCard(
                  wallet: _wallet,
                  loading: _loading,
                  showBalance: _showBalance,
                  onToggleBalance: () =>
                      setState(() => _showBalance = !_showBalance),
                  onRefresh: _refresh,
                ),
                if (_error != null) ...[
                  const SizedBox(height: 12),
                  _InlineError(message: _error!, onRetry: _refresh),
                ],
                const SizedBox(height: 24),
                const Text(
                  'Accesos rápidos',
                  style: TextStyle(
                    color: _ink,
                    fontSize: 17,
                    fontWeight: FontWeight.w700,
                  ),
                ),
                const SizedBox(height: 14),
                Row(
                  children: [
                    Expanded(
                      child: _QuickAction(
                        icon: Icons.north_east_rounded,
                        label: 'Enviar',
                        onTap: _openTransfer,
                      ),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: _QuickAction(
                        icon: Icons.south_west_rounded,
                        label: 'Recibir',
                        onTap: _receive,
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 12),
                Row(
                  children: [
                    Expanded(
                      child: _QuickAction(
                        icon: Icons.person_outline_rounded,
                        label: 'Mi perfil',
                        onTap: () => showModalBottomSheet<void>(
                          context: context,
                          showDragHandle: true,
                          builder: (context) => _ProfileSheet(
                            api: widget.api,
                            user: widget.user,
                            onUserUpdated: widget.onUserUpdated,
                            onSignOut: widget.onSignOut,
                          ),
                        ),
                      ),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: _QuickAction(
                        icon: Icons.receipt_long_outlined,
                        label: 'Historial',
                        onTap: _openHistory,
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 27),
                Row(
                  children: [
                    const Expanded(
                      child: Text(
                        'Movimientos recientes',
                        style: TextStyle(
                          color: _ink,
                          fontSize: 17,
                          fontWeight: FontWeight.w700,
                        ),
                      ),
                    ),
                    TextButton.icon(
                      onPressed: _loading ? null : _refresh,
                      icon: const Icon(Icons.refresh_rounded, size: 18),
                      label: const Text('Actualizar'),
                    ),
                  ],
                ),
                const SizedBox(height: 7),
                _MovementList(
                  movements: _wallet?.movements ?? [],
                  loading: _loading,
                  onMovementSelected: _openReceipt,
                ),
                const SizedBox(height: 18),
                const Text(
                  'Billetera de prueba · GTQ',
                  textAlign: TextAlign.center,
                  style: TextStyle(color: _muted, fontSize: 12),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _WalletCard extends StatelessWidget {
  const _WalletCard({
    required this.wallet,
    required this.loading,
    required this.showBalance,
    required this.onToggleBalance,
    required this.onRefresh,
  });

  final WalletSnapshot? wallet;
  final bool loading;
  final bool showBalance;
  final VoidCallback onToggleBalance;
  final VoidCallback onRefresh;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(24),
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(24),
        gradient: const LinearGradient(
          colors: [Color(0xFF17377F), Color(0xFF2457D6), Color(0xFF3B7BF4)],
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
        ),
        boxShadow: [
          BoxShadow(
            color: _blue.withValues(alpha: 0.20),
            blurRadius: 28,
            offset: const Offset(0, 14),
          ),
        ],
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              const Expanded(
                child: Text(
                  'SALDO DISPONIBLE',
                  style: TextStyle(
                    color: Colors.white70,
                    fontSize: 11,
                    letterSpacing: 1.3,
                  ),
                ),
              ),
              IconButton(
                tooltip: showBalance ? 'Ocultar saldo' : 'Mostrar saldo',
                onPressed: onToggleBalance,
                icon: Icon(
                  showBalance
                      ? Icons.visibility_outlined
                      : Icons.visibility_off_outlined,
                  color: Colors.white,
                  size: 20,
                ),
              ),
              IconButton(
                tooltip: 'Actualizar saldo',
                onPressed: loading ? null : onRefresh,
                icon: const Icon(
                  Icons.refresh_rounded,
                  color: Colors.white,
                  size: 20,
                ),
              ),
            ],
          ),
          const SizedBox(height: 7),
          AnimatedSwitcher(
            duration: const Duration(milliseconds: 220),
            child: Column(
              key: ValueKey('${wallet?.balance}-$showBalance-$loading'),
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  loading && wallet == null
                      ? 'Cargando…'
                      : showBalance && wallet != null
                      ? '${wallet!.currency} ${wallet!.availableBalance.toStringAsFixed(2)}'
                      : '••••••',
                  style: const TextStyle(
                    color: Colors.white,
                    fontSize: 32,
                    fontWeight: FontWeight.w700,
                    letterSpacing: -0.8,
                  ),
                ),
                if (wallet != null) ...[
                  const SizedBox(height: 8),
                  Text(
                    showBalance
                        ? 'Retenido ${wallet!.currency} ${wallet!.heldBalance.toStringAsFixed(2)}'
                        : 'Retenido •••',
                    style: const TextStyle(color: Colors.white70, fontSize: 12),
                  ),
                ],
              ],
            ),
          ),
          const SizedBox(height: 18),
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 11, vertical: 7),
            decoration: BoxDecoration(
              color: Colors.white.withValues(alpha: 0.15),
              borderRadius: BorderRadius.circular(30),
            ),
            child: const Text(
              'Cuenta personal · Sandbox',
              style: TextStyle(color: Colors.white, fontSize: 12),
            ),
          ),
        ],
      ),
    );
  }
}

class _QuickAction extends StatelessWidget {
  const _QuickAction({
    required this.icon,
    required this.label,
    required this.onTap,
  });

  final IconData icon;
  final String label;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Colors.white,
      borderRadius: BorderRadius.circular(18),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(18),
        child: Padding(
          padding: const EdgeInsets.symmetric(vertical: 17, horizontal: 8),
          child: Column(
            children: [
              Icon(icon, color: _blue, size: 24),
              const SizedBox(height: 9),
              Text(
                label,
                textAlign: TextAlign.center,
                style: const TextStyle(
                  color: _ink,
                  fontWeight: FontWeight.w600,
                  fontSize: 12,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _MovementList extends StatelessWidget {
  const _MovementList({
    required this.movements,
    required this.loading,
    this.onMovementSelected,
  });

  final List<Movement> movements;
  final bool loading;
  final ValueChanged<Movement>? onMovementSelected;

  @override
  Widget build(BuildContext context) {
    if (loading && movements.isEmpty) {
      return const Padding(
        padding: EdgeInsets.all(28),
        child: Center(child: CircularProgressIndicator()),
      );
    }
    if (movements.isEmpty) {
      return const _EmptyMovements();
    }
    return Card(
      elevation: 0,
      color: Colors.white,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
      child: Column(
        children: [
          for (var index = 0; index < movements.length; index++) ...[
            _MovementTile(
              movement: movements[index],
              onTap:
                  movements[index].type == 'TRANSFER' &&
                      onMovementSelected != null
                  ? () => onMovementSelected!(movements[index])
                  : null,
            ),
            if (index != movements.length - 1)
              const Divider(height: 1, indent: 66, endIndent: 18),
          ],
        ],
      ),
    );
  }
}

class _MovementTile extends StatelessWidget {
  const _MovementTile({required this.movement, this.onTap});

  final Movement movement;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final incoming = movement.amount >= 0;
    final date = movement.createdAt.toLocal();
    final dateLabel =
        '${date.day.toString().padLeft(2, '0')}/${date.month.toString().padLeft(2, '0')}/${date.year}';
    return ListTile(
      onTap: onTap,
      contentPadding: const EdgeInsets.symmetric(horizontal: 17, vertical: 4),
      leading: CircleAvatar(
        backgroundColor: incoming
            ? const Color(0xFFEAF4EF)
            : const Color(0xFFEEF2FC),
        child: Icon(
          incoming ? Icons.south_west_rounded : Icons.north_east_rounded,
          color: incoming ? const Color(0xFF2D8A5E) : _blue,
          size: 20,
        ),
      ),
      title: Text(
        movement.description,
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
        style: const TextStyle(
          color: _ink,
          fontWeight: FontWeight.w600,
          fontSize: 14,
        ),
      ),
      subtitle: Text(
        '$dateLabel · ${movement.status == 'COMPLETED' ? 'Completado' : movement.status}',
        style: const TextStyle(color: _muted, fontSize: 12),
      ),
      trailing: Text(
        '${incoming ? '+' : '−'} GTQ ${movement.amount.abs().toStringAsFixed(2)}',
        style: TextStyle(
          color: incoming ? const Color(0xFF2D8A5E) : _ink,
          fontWeight: FontWeight.w700,
          fontSize: 13,
        ),
      ),
    );
  }
}

class _EmptyMovements extends StatelessWidget {
  const _EmptyMovements();

  @override
  Widget build(BuildContext context) => Container(
    width: double.infinity,
    padding: const EdgeInsets.all(26),
    decoration: BoxDecoration(
      color: Colors.white,
      borderRadius: BorderRadius.circular(20),
    ),
    child: const Column(
      children: [
        Icon(Icons.receipt_long_outlined, color: _muted, size: 28),
        SizedBox(height: 9),
        Text(
          'Aquí aparecerán tus movimientos.',
          style: TextStyle(color: _muted),
        ),
      ],
    ),
  );
}

class MovementHistorySheet extends StatefulWidget {
  const MovementHistorySheet({
    required this.api,
    required this.onSelect,
    super.key,
  });

  final ApiClient api;
  final ValueChanged<Movement> onSelect;

  @override
  State<MovementHistorySheet> createState() => _MovementHistorySheetState();
}

class _MovementHistorySheetState extends State<MovementHistorySheet> {
  final List<Movement> _movements = [];
  bool _loading = false;
  bool _hasMore = true;
  String? _error;

  @override
  void initState() {
    super.initState();
    _loadMore();
  }

  Future<void> _loadMore() async {
    if (_loading || !_hasMore) return;
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final page = await widget.api.loadMovements(offset: _movements.length);
      if (!mounted) return;
      setState(() {
        _movements.addAll(page);
        _hasMore = page.length == 50;
      });
    } on ApiException catch (exception) {
      if (mounted) setState(() => _error = exception.message);
    } on Exception {
      if (mounted) setState(() => _error = 'No se pudo cargar el historial.');
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  @override
  Widget build(BuildContext context) => SafeArea(
    child: SizedBox(
      height: MediaQuery.sizeOf(context).height * 0.82,
      child: Column(
        children: [
          const Padding(
            padding: EdgeInsets.fromLTRB(20, 8, 20, 14),
            child: Align(
              alignment: Alignment.centerLeft,
              child: Text(
                'Historial de movimientos',
                style: TextStyle(
                  color: _ink,
                  fontSize: 20,
                  fontWeight: FontWeight.w700,
                ),
              ),
            ),
          ),
          if (_error != null)
            _InlineError(message: _error!, onRetry: _loadMore),
          Expanded(
            child: _movements.isEmpty && _loading
                ? const Center(child: CircularProgressIndicator())
                : _movements.isEmpty
                ? const _EmptyMovements()
                : ListView.separated(
                    itemCount: _movements.length + (_hasMore ? 1 : 0),
                    separatorBuilder: (_, _) =>
                        const Divider(height: 1, indent: 66, endIndent: 18),
                    itemBuilder: (context, index) {
                      if (index == _movements.length) {
                        return Padding(
                          padding: const EdgeInsets.all(14),
                          child: Center(
                            child: _loading
                                ? const CircularProgressIndicator()
                                : TextButton(
                                    onPressed: _loadMore,
                                    child: const Text('Cargar más'),
                                  ),
                          ),
                        );
                      }
                      final movement = _movements[index];
                      return _MovementTile(
                        movement: movement,
                        onTap: movement.type == 'TRANSFER'
                            ? () => widget.onSelect(movement)
                            : null,
                      );
                    },
                  ),
          ),
        ],
      ),
    ),
  );
}

class NotificationsSheet extends StatefulWidget {
  const NotificationsSheet({
    required this.api,
    required this.onSelectTransfer,
    super.key,
  });

  final ApiClient api;
  final ValueChanged<String> onSelectTransfer;

  @override
  State<NotificationsSheet> createState() => _NotificationsSheetState();
}

class _NotificationsSheetState extends State<NotificationsSheet> {
  List<AppNotification> _notifications = [];
  bool _loading = true;
  String? _error;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final notifications = await widget.api.loadNotifications();
      if (mounted) setState(() => _notifications = notifications);
    } on ApiException catch (exception) {
      if (mounted) setState(() => _error = exception.message);
    } on Exception {
      if (mounted) {
        setState(() => _error = 'No se pudieron cargar las alertas.');
      }
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  Future<void> _open(AppNotification notification) async {
    try {
      if (notification.readAt == null) {
        await widget.api.markNotificationRead(notification.id);
        await _load();
      }
      if (mounted) widget.onSelectTransfer(notification.transactionId);
    } on ApiException catch (exception) {
      if (mounted) setState(() => _error = exception.message);
    } on Exception {
      if (mounted) setState(() => _error = 'No se pudo abrir la notificación.');
    }
  }

  @override
  Widget build(BuildContext context) => SafeArea(
    child: SizedBox(
      height: MediaQuery.sizeOf(context).height * 0.72,
      child: Column(
        children: [
          const Padding(
            padding: EdgeInsets.fromLTRB(20, 8, 20, 14),
            child: Align(
              alignment: Alignment.centerLeft,
              child: Text(
                'Notificaciones',
                style: TextStyle(
                  color: _ink,
                  fontSize: 20,
                  fontWeight: FontWeight.w700,
                ),
              ),
            ),
          ),
          if (_error != null) _InlineError(message: _error!, onRetry: _load),
          Expanded(
            child: _loading
                ? const Center(child: CircularProgressIndicator())
                : _notifications.isEmpty
                ? const Center(child: Text('No tienes notificaciones nuevas.'))
                : ListView.separated(
                    itemCount: _notifications.length,
                    separatorBuilder: (_, _) =>
                        const Divider(height: 1, indent: 66, endIndent: 18),
                    itemBuilder: (context, index) {
                      final notification = _notifications[index];
                      return ListTile(
                        onTap: () => _open(notification),
                        leading: CircleAvatar(
                          backgroundColor: notification.readAt == null
                              ? const Color(0xFFEAF0FF)
                              : _canvas,
                          child: Icon(
                            notification.type == 'TRANSFER_RECEIVED'
                                ? Icons.south_west_rounded
                                : Icons.north_east_rounded,
                            color: _blue,
                          ),
                        ),
                        title: Text(
                          notification.message,
                          style: TextStyle(
                            color: _ink,
                            fontWeight: notification.readAt == null
                                ? FontWeight.w700
                                : FontWeight.w500,
                          ),
                        ),
                        subtitle: Text(
                          notification.createdAt.toLocal().toString(),
                          style: const TextStyle(color: _muted, fontSize: 12),
                        ),
                        trailing: notification.readAt == null
                            ? const Icon(Icons.circle, color: _blue, size: 9)
                            : null,
                      );
                    },
                  ),
          ),
        ],
      ),
    ),
  );
}

class TransferReceiptSheet extends StatelessWidget {
  const TransferReceiptSheet({
    required this.receipt,
    required this.api,
    required this.canReverseTransfers,
    required this.mfaEnabled,
    super.key,
  });

  final TransferReceipt receipt;
  final ApiClient api;
  final bool canReverseTransfers;
  final bool mfaEnabled;

  @override
  Widget build(BuildContext context) {
    final reversal = receipt.operationType == 'REVERSAL';
    final outgoing = receipt.direction == 'SENT';
    return SafeArea(
      child: SingleChildScrollView(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(22, 8, 22, 24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const Icon(
                Icons.check_circle_rounded,
                color: Color(0xFF2D8A5E),
                size: 52,
              ),
              const SizedBox(height: 10),
              Text(
                reversal
                    ? 'Reversión completada'
                    : outgoing
                    ? 'Transferencia enviada'
                    : 'Transferencia recibida',
                textAlign: TextAlign.center,
                style: const TextStyle(
                  color: _ink,
                  fontSize: 21,
                  fontWeight: FontWeight.w700,
                ),
              ),
              const SizedBox(height: 5),
              Text(
                '${receipt.currency} ${receipt.amount.toStringAsFixed(2)}',
                textAlign: TextAlign.center,
                style: const TextStyle(
                  color: _blue,
                  fontSize: 28,
                  fontWeight: FontWeight.w800,
                ),
              ),
              const SizedBox(height: 18),
              _ReceiptRow(
                label: reversal
                    ? 'Efecto'
                    : outgoing
                    ? 'Destinatario'
                    : 'Remitente',
                value: reversal
                    ? receipt.direction == 'REVERSAL_CREDIT'
                          ? 'Abono a tu billetera'
                          : receipt.direction == 'REVERSAL_DEBIT'
                          ? 'Débito a tu billetera'
                          : 'Operación autorizada'
                    : '${receipt.counterpartyName} · ${receipt.maskedCounterpartyEmail}',
              ),
              if (reversal && receipt.originalTransactionId != null)
                _ReceiptRow(
                  label: 'Transferencia original',
                  value: receipt.originalTransactionId!,
                ),
              if (reversal && receipt.reason != null)
                _ReceiptRow(label: 'Motivo', value: receipt.reason!),
              _ReceiptRow(label: 'Estado', value: receipt.status),
              _ReceiptRow(
                label: 'Fecha',
                value: receipt.createdAt.toLocal().toString(),
              ),
              _ReceiptRow(label: 'Comprobante', value: receipt.receiptNumber),
              const SizedBox(height: 12),
              OutlinedButton.icon(
                onPressed: () async {
                  await Clipboard.setData(
                    ClipboardData(text: receipt.receiptNumber),
                  );
                  if (context.mounted) {
                    ScaffoldMessenger.of(context).showSnackBar(
                      const SnackBar(
                        content: Text('Número de comprobante copiado'),
                      ),
                    );
                  }
                },
                icon: const Icon(Icons.copy_rounded),
                label: const Text('Copiar número de comprobante'),
              ),
              if (canReverseTransfers &&
                  !reversal &&
                  receipt.status == 'COMPLETED') ...[
                const SizedBox(height: 10),
                OutlinedButton.icon(
                  onPressed: () async {
                    final reversalInput = await showDialog<_ReversalInput>(
                      context: context,
                      builder: (context) =>
                          TransferReversalDialog(mfaEnabled: mfaEnabled),
                    );
                    if (reversalInput == null) return;
                    try {
                      final result = await api.reverseTransfer(
                        receipt.transactionId,
                        reversalInput.reason,
                        reversalInput.password,
                        reversalInput.mfaFactor,
                        _newIdempotencyKey(),
                      );
                      if (context.mounted) {
                        Navigator.of(context).pop(result);
                      }
                    } on ApiException catch (exception) {
                      if (context.mounted) {
                        ScaffoldMessenger.of(context).showSnackBar(
                          SnackBar(content: Text(exception.message)),
                        );
                      }
                    } on Exception {
                      if (context.mounted) {
                        ScaffoldMessenger.of(context).showSnackBar(
                          const SnackBar(
                            content: Text(
                              'No se pudo revertir la transferencia.',
                            ),
                          ),
                        );
                      }
                    }
                  },
                  icon: const Icon(Icons.undo_rounded),
                  label: const Text('Revertir transferencia'),
                ),
              ],
              const SizedBox(height: 5),
              const Text(
                'Comprobante digital de Sandbox; no representa una operación bancaria.',
                textAlign: TextAlign.center,
                style: TextStyle(color: _muted, fontSize: 12),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _ReversalInput {
  const _ReversalInput({
    required this.reason,
    required this.password,
    required this.mfaFactor,
  });

  final String reason;
  final String password;
  final String? mfaFactor;
}

class TransferReversalDialog extends StatefulWidget {
  const TransferReversalDialog({required this.mfaEnabled, super.key});

  final bool mfaEnabled;

  @override
  State<TransferReversalDialog> createState() => _TransferReversalDialogState();
}

class _TransferReversalDialogState extends State<TransferReversalDialog> {
  final _formKey = GlobalKey<FormState>();
  final _reason = TextEditingController();
  final _password = TextEditingController();
  final _mfaFactor = TextEditingController();

  @override
  void dispose() {
    _reason.dispose();
    _password.dispose();
    _mfaFactor.dispose();
    super.dispose();
  }

  void _submit() {
    if (!_formKey.currentState!.validate()) return;
    Navigator.of(context).pop(
      _ReversalInput(
        reason: _reason.text.trim(),
        password: _password.text,
        mfaFactor: widget.mfaEnabled ? _mfaFactor.text.trim() : null,
      ),
    );
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
    title: const Text('Confirmar reversión'),
    content: Form(
      key: _formKey,
      child: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextFormField(
              controller: _reason,
              maxLength: 500,
              maxLines: 3,
              decoration: const InputDecoration(
                labelText: 'Motivo obligatorio',
              ),
              validator: (value) => value == null || value.trim().isEmpty
                  ? 'Escribe el motivo de la reversión'
                  : null,
            ),
            TextFormField(
              controller: _password,
              obscureText: true,
              decoration: const InputDecoration(labelText: 'Contraseña'),
              validator: (value) => value == null || value.isEmpty
                  ? 'Ingresa tu contraseña'
                  : null,
            ),
            if (widget.mfaEnabled)
              TextFormField(
                controller: _mfaFactor,
                decoration: const InputDecoration(
                  labelText: 'Código MFA o recuperación',
                ),
                validator: (value) => value == null || value.trim().isEmpty
                    ? 'Ingresa tu segundo factor'
                    : null,
              ),
          ],
        ),
      ),
    ),
    actions: [
      TextButton(
        onPressed: () => Navigator.of(context).pop(),
        child: const Text('Cancelar'),
      ),
      FilledButton(
        onPressed: _submit,
        child: const Text('Confirmar reversión'),
      ),
    ],
  );
}

class _ReceiptRow extends StatelessWidget {
  const _ReceiptRow({required this.label, required this.value});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 7),
    child: Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        SizedBox(
          width: 105,
          child: Text(label, style: const TextStyle(color: _muted)),
        ),
        Expanded(
          child: Text(
            value,
            textAlign: TextAlign.end,
            style: const TextStyle(color: _ink, fontWeight: FontWeight.w600),
          ),
        ),
      ],
    ),
  );
}

class _ProfileSheet extends StatefulWidget {
  const _ProfileSheet({
    required this.api,
    required this.user,
    required this.onUserUpdated,
    required this.onSignOut,
  });

  final ApiClient api;
  final AuthUser user;
  final ValueChanged<AuthUser> onUserUpdated;
  final Future<void> Function() onSignOut;

  @override
  State<_ProfileSheet> createState() => _ProfileSheetState();
}

class _ProfileSheetState extends State<_ProfileSheet> {
  bool _busy = false;
  String? _error;

  Future<void> _configureMfa() async {
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      if (widget.user.mfaEnabled) {
        await _disableMfa();
      } else {
        await _enableMfa();
      }
    } on ApiException catch (exception) {
      if (mounted) setState(() => _error = exception.message);
    } on Exception {
      if (mounted) {
        setState(
          () => _error = 'No se pudo completar la configuración de MFA.',
        );
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _enableMfa() async {
    final password = await showDialog<String>(
      context: context,
      builder: (context) => const _PasswordDialog(
        title: 'Confirma tu contraseña',
        description:
            'Necesitamos verificar tu contraseña antes de activar MFA.',
      ),
    );
    if (!mounted || password == null) return;

    final setup = await widget.api.beginMfaEnrollment(password);
    if (!mounted) return;
    final secret = setup['secret'] as String;
    final code = await showDialog<String>(
      context: context,
      builder: (context) => _MfaEnrollmentDialog(secret: secret),
    );
    if (!mounted || code == null) return;

    final result = await widget.api.confirmMfaEnrollment(code);
    if (!mounted) return;
    final recoveryCodes = result['recoveryCodes'];
    if (result['enabled'] != true ||
        recoveryCodes is! List<dynamic> ||
        recoveryCodes.any((item) => item is! String)) {
      throw const FormatException('Respuesta de recuperación MFA inválida');
    }
    await showDialog<void>(
      context: context,
      barrierDismissible: false,
      builder: (context) =>
          _RecoveryCodesDialog(codes: recoveryCodes.cast<String>()),
    );
    if (mounted) widget.onUserUpdated(widget.user.withMfaEnabled(true));
  }

  Future<void> _disableMfa() async {
    final credentials = await showDialog<Map<String, String>>(
      context: context,
      builder: (context) => const _DisableMfaDialog(),
    );
    if (!mounted || credentials == null) return;
    await widget.api.disableMfa(
      credentials['password']!,
      credentials['factor']!,
    );
    if (!mounted) return;
    widget.onUserUpdated(widget.user.withMfaEnabled(false));
    await widget.onSignOut();
  }

  @override
  Widget build(BuildContext context) => SafeArea(
    child: Padding(
      padding: const EdgeInsets.fromLTRB(24, 8, 24, 24),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text(
            'Mi perfil',
            style: TextStyle(
              color: _ink,
              fontSize: 21,
              fontWeight: FontWeight.w700,
            ),
          ),
          const SizedBox(height: 18),
          _ProfileRow(label: 'Nombre', value: widget.user.fullName),
          _ProfileRow(label: 'Correo', value: widget.user.email),
          _ProfileRow(
            label: 'Verificación del correo',
            value: widget.user.emailVerified ? 'Verificado' : 'Pendiente',
          ),
          const _ProfileRow(
            label: 'Verificación de identidad',
            value: 'Pendiente',
          ),
          _ProfileRow(
            label: 'Autenticación multifactor',
            value: widget.user.mfaEnabled ? 'Activada' : 'Desactivada',
          ),
          if (_error != null) ...[
            const SizedBox(height: 8),
            Text(_error!, style: const TextStyle(color: Color(0xFFB3261E))),
          ],
          const SizedBox(height: 12),
          OutlinedButton.icon(
            onPressed: _busy ? null : _configureMfa,
            icon: _busy
                ? const SizedBox(
                    width: 18,
                    height: 18,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  )
                : Icon(
                    widget.user.mfaEnabled
                        ? Icons.lock_reset_rounded
                        : Icons.security_rounded,
                  ),
            label: Text(
              widget.user.mfaEnabled ? 'Desactivar MFA' : 'Activar MFA',
            ),
            style: OutlinedButton.styleFrom(
              minimumSize: const Size.fromHeight(48),
              foregroundColor: _ink,
            ),
          ),
          const SizedBox(height: 8),
          OutlinedButton.icon(
            onPressed: _busy
                ? null
                : () async {
                    final messenger = ScaffoldMessenger.of(context);
                    Navigator.pop(context);
                    try {
                      await widget.onSignOut();
                    } on ApiException {
                      messenger.showSnackBar(
                        const SnackBar(
                          content: Text(
                            'La sesión local se cerró, pero no se confirmó la revocación remota.',
                          ),
                        ),
                      );
                    } on Exception {
                      messenger.showSnackBar(
                        const SnackBar(
                          content: Text(
                            'La sesión local se cerró, pero no se confirmó la revocación remota.',
                          ),
                        ),
                      );
                    }
                  },
            icon: const Icon(Icons.logout_rounded),
            label: const Text('Cerrar sesión'),
            style: OutlinedButton.styleFrom(
              minimumSize: const Size.fromHeight(48),
              foregroundColor: _ink,
            ),
          ),
        ],
      ),
    ),
  );
}

class _PasswordDialog extends StatefulWidget {
  const _PasswordDialog({required this.title, required this.description});

  final String title;
  final String description;

  @override
  State<_PasswordDialog> createState() => _PasswordDialogState();
}

class _PasswordDialogState extends State<_PasswordDialog> {
  final _formKey = GlobalKey<FormState>();
  final _password = TextEditingController();

  @override
  void dispose() {
    _password.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
    title: Text(widget.title),
    content: Form(
      key: _formKey,
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(widget.description),
          const SizedBox(height: 16),
          TextFormField(
            controller: _password,
            autofocus: true,
            obscureText: true,
            decoration: const InputDecoration(labelText: 'Contraseña actual'),
            validator: (value) =>
                (value?.isNotEmpty ?? false) ? null : 'Ingresa tu contraseña',
          ),
        ],
      ),
    ),
    actions: [
      TextButton(
        onPressed: () => Navigator.pop(context),
        child: const Text('Cancelar'),
      ),
      FilledButton(
        onPressed: () {
          if (_formKey.currentState!.validate()) {
            Navigator.pop(context, _password.text);
          }
        },
        child: const Text('Continuar'),
      ),
    ],
  );
}

class _MfaEnrollmentDialog extends StatefulWidget {
  const _MfaEnrollmentDialog({required this.secret});

  final String secret;

  @override
  State<_MfaEnrollmentDialog> createState() => _MfaEnrollmentDialogState();
}

class _MfaEnrollmentDialogState extends State<_MfaEnrollmentDialog> {
  final _formKey = GlobalKey<FormState>();
  final _code = TextEditingController();

  @override
  void dispose() {
    _code.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
    title: const Text('Configura tu app autenticadora'),
    content: Form(
      key: _formKey,
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text(
            'Agrega Cobalto manualmente en una app TOTP. Este secreto se muestra una sola vez.',
          ),
          const SizedBox(height: 12),
          SelectableText(
            widget.secret,
            style: const TextStyle(
              fontWeight: FontWeight.w700,
              letterSpacing: 1.2,
            ),
          ),
          Align(
            alignment: Alignment.centerRight,
            child: TextButton.icon(
              onPressed: () =>
                  Clipboard.setData(ClipboardData(text: widget.secret)),
              icon: const Icon(Icons.copy_rounded),
              label: const Text('Copiar secreto'),
            ),
          ),
          TextFormField(
            controller: _code,
            autofocus: true,
            keyboardType: TextInputType.number,
            decoration: const InputDecoration(labelText: 'Código de 6 dígitos'),
            validator: (value) =>
                RegExp(r'^\d{6}$').hasMatch(value?.trim() ?? '')
                ? null
                : 'Ingresa el código actual',
          ),
        ],
      ),
    ),
    actions: [
      TextButton(
        onPressed: () => Navigator.pop(context),
        child: const Text('Cancelar'),
      ),
      FilledButton(
        onPressed: () {
          if (_formKey.currentState!.validate()) {
            Navigator.pop(context, _code.text.trim());
          }
        },
        child: const Text('Activar'),
      ),
    ],
  );
}

class _DisableMfaDialog extends StatefulWidget {
  const _DisableMfaDialog();

  @override
  State<_DisableMfaDialog> createState() => _DisableMfaDialogState();
}

class _DisableMfaDialogState extends State<_DisableMfaDialog> {
  final _formKey = GlobalKey<FormState>();
  final _password = TextEditingController();
  final _factor = TextEditingController();

  @override
  void dispose() {
    _password.dispose();
    _factor.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
    title: const Text('Desactivar MFA'),
    content: Form(
      key: _formKey,
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          TextFormField(
            controller: _password,
            obscureText: true,
            decoration: const InputDecoration(labelText: 'Contraseña actual'),
            validator: (value) =>
                (value?.isNotEmpty ?? false) ? null : 'Ingresa tu contraseña',
          ),
          const SizedBox(height: 12),
          TextFormField(
            controller: _factor,
            decoration: const InputDecoration(
              labelText: 'Código TOTP o de recuperación',
            ),
            validator: (value) {
              final code = value?.trim() ?? '';
              if (RegExp(r'^\d{6}$').hasMatch(code) ||
                  RegExp(r'^(?:[0-9a-fA-F]{4}-?){7}[0-9a-fA-F]{4}$')
                      .hasMatch(code)) {
                return null;
              }
              return 'Ingresa un código válido';
            },
          ),
        ],
      ),
    ),
    actions: [
      TextButton(
        onPressed: () => Navigator.pop(context),
        child: const Text('Cancelar'),
      ),
      FilledButton(
        onPressed: () {
          if (_formKey.currentState!.validate()) {
            Navigator.pop(context, {
              'password': _password.text,
              'factor': _factor.text.trim(),
            });
          }
        },
        child: const Text('Desactivar'),
      ),
    ],
  );
}

class _RecoveryCodesDialog extends StatelessWidget {
  const _RecoveryCodesDialog({required this.codes});

  final List<String> codes;

  @override
  Widget build(BuildContext context) => AlertDialog(
    title: const Text('Guarda tus códigos de recuperación'),
    content: Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Text('Cada código sirve una sola vez. No volverán a mostrarse.'),
        const SizedBox(height: 16),
        SelectableText(codes.join('\n')),
        Align(
          alignment: Alignment.centerRight,
          child: TextButton.icon(
            onPressed: () =>
                Clipboard.setData(ClipboardData(text: codes.join('\n'))),
            icon: const Icon(Icons.copy_rounded),
            label: const Text('Copiar códigos'),
          ),
        ),
      ],
    ),
    actions: [
      FilledButton(
        onPressed: () => Navigator.pop(context),
        child: const Text('Los guardé'),
      ),
    ],
  );
}

class _ProfileRow extends StatelessWidget {
  const _ProfileRow({required this.label, required this.value});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(bottom: 15),
    child: Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        SizedBox(
          width: 170,
          child: Text(label, style: const TextStyle(color: _muted)),
        ),
        Expanded(
          child: Text(
            value,
            style: const TextStyle(color: _ink, fontWeight: FontWeight.w600),
          ),
        ),
      ],
    ),
  );
}

class TransferDialog extends StatefulWidget {
  const TransferDialog({
    required this.api,
    required this.mfaEnabled,
    super.key,
  });

  final ApiClient api;
  final bool mfaEnabled;

  @override
  State<TransferDialog> createState() => _TransferDialogState();
}

class _TransferDialogState extends State<TransferDialog> {
  final _formKey = GlobalKey<FormState>();
  final _email = TextEditingController();
  final _amount = TextEditingController();
  final _password = TextEditingController();
  final _mfaFactor = TextEditingController();
  RecipientConfirmation? _recipient;
  String _idempotencyKey = _newIdempotencyKey();
  bool _busy = false;
  bool _checkingRecipient = false;
  String? _error;

  @override
  void dispose() {
    _email.dispose();
    _amount.dispose();
    _password.dispose();
    _mfaFactor.dispose();
    super.dispose();
  }

  Future<void> _confirmRecipient() async {
    final email = _email.text.trim();
    if (!RegExp(r'^[^@\s]+@[^@\s]+\.[^@\s]+$').hasMatch(email)) {
      setState(() => _error = 'Ingresa un correo válido.');
      return;
    }
    setState(() {
      _checkingRecipient = true;
      _error = null;
    });
    try {
      final recipient = await widget.api.confirmRecipient(email);
      if (mounted) setState(() => _recipient = recipient);
    } on ApiException catch (exception) {
      if (mounted) setState(() => _error = exception.message);
    } on Exception {
      if (mounted) setState(() => _error = 'No se pudo confirmar la cuenta.');
    } finally {
      if (mounted) setState(() => _checkingRecipient = false);
    }
  }

  Future<void> _send() async {
    if (_recipient == null || !_formKey.currentState!.validate()) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final receipt = await widget.api.transfer(
        _email.text,
        _amount.text.trim(),
        _password.text,
        widget.mfaEnabled ? _mfaFactor.text.trim() : null,
        _idempotencyKey,
      );
      if (mounted) Navigator.pop(context, receipt);
    } on ApiException catch (exception) {
      if (mounted) setState(() => _error = exception.message);
    } on Exception {
      if (mounted) {
        setState(
          () =>
              _error = 'No se pudo confirmar. Reintenta sin cambiar los datos.',
        );
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
    title: const Text('Enviar dinero'),
    content: Form(
      key: _formKey,
      child: SizedBox(
        width: 390,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              TextFormField(
                controller: _email,
                enabled: !_busy && !_checkingRecipient && _recipient == null,
                keyboardType: TextInputType.emailAddress,
                decoration: const InputDecoration(
                  labelText: 'Correo del destinatario',
                ),
                onChanged: (_) => setState(() {
                  _recipient = null;
                  _idempotencyKey = _newIdempotencyKey();
                }),
                validator: (value) =>
                    RegExp(r'^[^@\s]+@[^@\s]+\.[^@\s]+$')
                        .hasMatch(value?.trim() ?? '')
                    ? null
                    : 'Ingresa un correo válido',
              ),
              if (_recipient == null) ...[
                const SizedBox(height: 10),
                Align(
                  alignment: Alignment.centerRight,
                  child: TextButton.icon(
                    onPressed: _busy || _checkingRecipient
                        ? null
                        : _confirmRecipient,
                    icon: _checkingRecipient
                        ? const SizedBox(
                            width: 16,
                            height: 16,
                            child: CircularProgressIndicator(strokeWidth: 2),
                          )
                        : const Icon(Icons.person_search_outlined),
                    label: const Text('Confirmar destinatario'),
                  ),
                ),
              ],
              if (_recipient != null) ...[
                const SizedBox(height: 8),
                Card(
                  elevation: 0,
                  color: const Color(0xFFEAF0FF),
                  child: ListTile(
                    leading: const CircleAvatar(
                      child: Icon(Icons.person_outline_rounded),
                    ),
                    title: Text(
                      _recipient!.fullName,
                      style: const TextStyle(fontWeight: FontWeight.w700),
                    ),
                    subtitle: Text(_recipient!.maskedEmail),
                    trailing: IconButton(
                      tooltip: 'Cambiar destinatario',
                      onPressed: _busy
                          ? null
                          : () => setState(() {
                              _recipient = null;
                              _idempotencyKey = _newIdempotencyKey();
                            }),
                      icon: const Icon(Icons.edit_outlined),
                    ),
                  ),
                ),
                const SizedBox(height: 12),
                TextFormField(
                  controller: _amount,
                  keyboardType: const TextInputType.numberWithOptions(
                    decimal: true,
                  ),
                  decoration: const InputDecoration(labelText: 'Monto en GTQ'),
                  onChanged: (_) => _idempotencyKey = _newIdempotencyKey(),
                  validator: (value) {
                    final amount = value?.trim() ?? '';
                    if (!RegExp(r'^(0|[1-9]\d{0,14})(\.\d{1,2})?$')
                        .hasMatch(amount)) {
                      return 'Ingresa un monto válido (máx. 2 decimales)';
                    }
                    if (!RegExp(r'[1-9]').hasMatch(amount)) {
                      return 'El monto debe ser mayor que cero';
                    }
                    return null;
                  },
                ),
                const SizedBox(height: 12),
                TextFormField(
                  controller: _password,
                  obscureText: true,
                  autofillHints: const [AutofillHints.password],
                  decoration: const InputDecoration(
                    labelText: 'Confirma tu contraseña',
                  ),
                  validator: (value) => (value?.isNotEmpty ?? false)
                      ? null
                      : 'Confirma tu contraseña',
                ),
                if (widget.mfaEnabled) ...[
                  const SizedBox(height: 12),
                  TextFormField(
                    controller: _mfaFactor,
                    decoration: const InputDecoration(
                      labelText: 'Código MFA o código de recuperación',
                    ),
                    validator: (value) {
                      final factor = value?.trim() ?? '';
                      final recovery = factor.replaceAll(RegExp(r'[\s-]'), '');
                      if (RegExp(r'^\d{6}$').hasMatch(factor) ||
                          RegExp(r'^[0-9A-Fa-f]{32}$').hasMatch(recovery)) {
                        return null;
                      }
                      return 'Ingresa un código MFA o de recuperación válido';
                    },
                  ),
                ],
                const SizedBox(height: 8),
                const Text(
                  'La operación se liquida al confirmar y usa solo saldo Sandbox.',
                  style: TextStyle(color: _muted, fontSize: 12),
                ),
              ],
              if (_error != null) ...[
                const SizedBox(height: 12),
                Text(_error!, style: const TextStyle(color: Color(0xFFB3261E))),
              ],
            ],
          ),
        ),
      ),
    ),
    actions: [
      TextButton(
        onPressed: _busy || _checkingRecipient
            ? null
            : () => Navigator.pop(context),
        child: const Text('Cancelar'),
      ),
      FilledButton(
        onPressed: _busy || _checkingRecipient || _recipient == null
            ? null
            : _send,
        child: _busy
            ? const SizedBox(
                width: 18,
                height: 18,
                child: CircularProgressIndicator(strokeWidth: 2),
              )
            : const Text('Enviar'),
      ),
    ],
  );
}

class _InlineError extends StatelessWidget {
  const _InlineError({required this.message, required this.onRetry});

  final String message;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) => Card(
    elevation: 0,
    color: const Color(0xFFFFF1F0),
    child: ListTile(
      leading: const Icon(Icons.error_outline, color: Color(0xFFB3261E)),
      title: Text(message, style: const TextStyle(color: _ink)),
      trailing: IconButton(onPressed: onRetry, icon: const Icon(Icons.refresh)),
    ),
  );
}

class _BrandMark extends StatelessWidget {
  const _BrandMark({this.compact = false});

  final bool compact;

  @override
  Widget build(BuildContext context) => Row(
    mainAxisSize: MainAxisSize.min,
    children: [
      Container(
        width: compact ? 34 : 43,
        height: compact ? 34 : 43,
        decoration: BoxDecoration(
          color: _blue,
          borderRadius: BorderRadius.circular(compact ? 11 : 14),
        ),
        child: Icon(
          Icons.water_drop_rounded,
          color: Colors.white,
          size: compact ? 19 : 23,
        ),
      ),
      const SizedBox(width: 10),
      Text(
        'cobalto',
        style: TextStyle(
          color: _ink,
          fontSize: compact ? 19 : 22,
          fontWeight: FontWeight.w800,
          letterSpacing: -0.7,
        ),
      ),
    ],
  );
}
