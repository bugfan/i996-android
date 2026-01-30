module github.com/bugfan/i996-android/mobile

go 1.24.0

toolchain go1.24.2

require github.com/bugfan/i996-android/tunnel/conn v0.0.0

require (
	golang.org/x/mobile v0.0.0-20260120165949-40bd9ace6ce4 // indirect
	golang.org/x/mod v0.32.0 // indirect
	golang.org/x/sync v0.19.0 // indirect
	golang.org/x/tools v0.41.0 // indirect
)

replace github.com/bugfan/i996-android/tunnel/conn => ../tunnel/conn
