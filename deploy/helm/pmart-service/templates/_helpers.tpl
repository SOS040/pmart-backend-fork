{{- define "pmart.name" -}}
{{- required "name(서비스 이름)이 필요하다" .Values.name -}}
{{- end -}}

{{- define "pmart.labels" -}}
app.kubernetes.io/name: {{ include "pmart.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/part-of: pmart
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version }}
{{- end -}}

{{- define "pmart.selectorLabels" -}}
app.kubernetes.io/name: {{ include "pmart.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "pmart.image" -}}
{{- $digest := required "image.digest 가 필요하다(태그 배포 금지, ci-cd.md)" .Values.image.digest -}}
{{- if not (hasPrefix "sha256:" $digest) -}}
{{- fail "image.digest 는 sha256:... 형식이어야 한다" -}}
{{- end -}}
{{ .Values.image.repository }}@{{ $digest }}
{{- end -}}
