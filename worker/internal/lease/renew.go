package lease

import (
	"context"
	"log"
	"time"
)

const DefaultRenewInterval = 10 * time.Second

func RunLoop(ctx context.Context, interval time.Duration, renew func(context.Context) error) {
	if interval <= 0 {
		interval = DefaultRenewInterval
	}
	if err := renew(ctx); err != nil {
		log.Printf("event=lease_renew_failed err=%v", err)
	}
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			if err := renew(ctx); err != nil {
				log.Printf("event=lease_renew_failed err=%v", err)
			}
		}
	}
}
