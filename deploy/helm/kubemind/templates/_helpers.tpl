{{/* Base name */}}
{{- define "kubemind.name" -}}
{{- .Chart.Name | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/* Fully qualified release-scoped name */}}
{{- define "kubemind.fullname" -}}
{{- printf "%s-%s" .Release.Name (include "kubemind.name" .) | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "kubemind.labels" -}}
app.kubernetes.io/name: {{ include "kubemind.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version }}
{{- end -}}

{{- define "kubemind.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "kubemind.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- required "serviceAccount.name is required when serviceAccount.create is false" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}

{{/* Secret holding admin password / encryption key / db password */}}
{{- define "kubemind.secretName" -}}
{{- default (printf "%s-secrets" (include "kubemind.fullname" .)) .Values.auth.existingSecret -}}
{{- end -}}

{{/* JDBC URL: bundled Postgres service, or the user's external one */}}
{{- define "kubemind.jdbcUrl" -}}
{{- if .Values.postgresql.enabled -}}
jdbc:postgresql://{{ include "kubemind.fullname" . }}-postgresql:5432/{{ .Values.postgresql.database }}
{{- else -}}
{{- required "postgresql.external.jdbcUrl is required when postgresql.enabled is false" .Values.postgresql.external.jdbcUrl -}}
{{- end -}}
{{- end -}}

{{- define "kubemind.dbUsername" -}}
{{- if .Values.postgresql.enabled -}}
{{- .Values.postgresql.username -}}
{{- else -}}
{{- required "postgresql.external.username is required when postgresql.enabled is false" .Values.postgresql.external.username -}}
{{- end -}}
{{- end -}}

{{/* Ollama base URL: bundled service, or the user's external one */}}
{{- define "kubemind.ollamaBaseUrl" -}}
{{- if .Values.ollama.enabled -}}
http://{{ include "kubemind.fullname" . }}-ollama:11434
{{- else -}}
{{- required "ollama.external.baseUrl is required when ollama.enabled is false" .Values.ollama.external.baseUrl -}}
{{- end -}}
{{- end -}}
