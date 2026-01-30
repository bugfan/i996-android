module github.com/bugfan/i996-android

go 1.24.2

require (
	github.com/bugfan/clotho/i996/engine/tcpmapping v0.0.0-00010101000000-000000000000
	github.com/bugfan/clotho/i996/engine/tunnel/cert v0.0.0-00010101000000-000000000000
	github.com/bugfan/clotho/i996/engine/tunnel/conn v0.0.0-00010101000000-000000000000
	github.com/bugfan/clotho/services/dao v0.0.0-00010101000000-000000000000
)

require (
	golang.org/x/mobile v0.0.0-20260120165949-40bd9ace6ce4 // indirect
	golang.org/x/mod v0.32.0 // indirect
	golang.org/x/sync v0.19.0 // indirect
	golang.org/x/tools v0.41.0 // indirect
)

// Replace missing dependencies with local dummy modules
replace github.com/bugfan/clotho/i996/engine/tcpmapping => ./tunnel/dummy/tcpmapping

replace github.com/bugfan/clotho/i996/engine/tunnel/cert => ./tunnel/dummy/cert

replace github.com/bugfan/clotho/i996/engine/tunnel/conn => ./tunnel/dummy/conn

replace github.com/bugfan/clotho/services/dao => ./tunnel/dummy/dao
