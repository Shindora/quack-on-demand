"""One httpx client for the manager REST plane.

Auth: X-API-Key carries either the static api_key or the session JWT written
by `qod login` (api_key wins when both are set, matching apiKeyGuard which
accepts either).
"""

from __future__ import annotations

from typing import Any

import httpx

from .config import Settings


# Endpoints that check a credential carried in the body. Their 401 means that credential is
# wrong, so the server's message passes through instead of the "session expired" hint.
_CREDENTIAL_PATHS = frozenset(
    {
        "/api/auth/login",
        "/api/auth/change-password",
        "/api/auth/forgot-password",
        "/api/auth/reset-password",
    }
)


class ApiError(Exception):
    def __init__(self, status: int, error: str, message: str, details: dict | None = None,
                 actions: list | None = None, retryable: bool | None = None):
        self.status = status
        self.error = error
        self.message = message
        # The manager's facts and suggested fixes (fleet errors today); None when it sent none.
        self.details = details if isinstance(details, dict) else None
        self.actions = actions if isinstance(actions, list) else None
        self.retryable = retryable if isinstance(retryable, bool) else None
        super().__init__(f"{status} {error}: {message}")


class RestClient:
    def __init__(self, settings: Settings):
        self._settings = settings

    def request(
        self,
        method: str,
        path: str,
        params: dict | None = None,
        body: Any | None = None,
        text: bool = False,
    ) -> Any:
        settings = self._settings
        headers = {}
        key = settings.api_key or settings.token
        if key:
            headers["X-API-Key"] = key
        query = {}
        for name, value in (params or {}).items():
            if value is None:
                continue
            query[name] = "true" if value is True else "false" if value is False else str(value)
        kwargs: dict = {"params": query, "headers": headers, "timeout": 30.0}
        if isinstance(body, str):
            kwargs["content"] = body.encode()
            headers["Content-Type"] = "text/plain"
        elif body is not None:
            kwargs["json"] = body
        try:
            response = httpx.request(method, settings.manager_url + path, **kwargs)
        except httpx.HTTPError as exc:
            raise ApiError(0, "connection_error", f"{settings.manager_url}: {exc}")
        if response.is_success:
            if text:
                return response.text
            if not response.content:
                return None
            return response.json()
        details = actions = retryable = None
        try:
            payload = response.json()
            if isinstance(payload, dict):
                error, message = payload.get("error", "error"), payload.get("message", response.text)
                details, actions, retryable = (
                    payload.get("details"), payload.get("actions"), payload.get("retryable"))
            else:
                error, message = "error", response.text
        except ValueError:
            error, message = "error", response.text or response.reason_phrase
        if (
            response.status_code == 401
            and not settings.api_key
            and path not in _CREDENTIAL_PATHS
        ):
            message, actions = "session expired or invalid, run qod login", None
        raise ApiError(response.status_code, error, message, details, actions, retryable)
