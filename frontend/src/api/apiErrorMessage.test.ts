import { describe, it, expect } from 'vitest'
import { AxiosError, AxiosHeaders } from 'axios'
import { apiErrorMessage } from './client'

/**
 * Every list page renders load failures through this. It exists because an
 * axios error's own `.message` is "Request failed with status code 403" — the
 * server's explanation of *why* sits in the response body, and pages that read
 * `(error as Error).message` instead were throwing it away.
 */
describe('apiErrorMessage', () => {
  function axios403(body: unknown) {
    const error = new AxiosError('Request failed with status code 403')
    error.response = {
      status: 403, statusText: 'Forbidden', data: body,
      headers: new AxiosHeaders(), config: { headers: new AxiosHeaders() },
    }
    return error
  }

  it('prefers the server explanation over axios status text', () => {
    const explained = 'ServiceAccount "kubemind-developer" (namespace dev-team) is not allowed to '
      + 'list secrets in namespace dev-team.'

    expect(apiErrorMessage(axios403({ error: explained }))).toBe(explained)
  })

  it('falls back when the response carries no explanation', () => {
    expect(apiErrorMessage(axios403({}), 'Could not load')).toBe('Could not load')
  })

  it('falls back for failures that never reached the server', () => {
    expect(apiErrorMessage(new Error('Network Error'), 'Could not load')).toBe('Could not load')
    expect(apiErrorMessage(undefined, 'Could not load')).toBe('Could not load')
  })
})
