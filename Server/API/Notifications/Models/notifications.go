package notifications

type Notification struct {
	ID string `json:"id"`
	UserId string `json:"user"`
	Message string `json:"message"`
	Url string `json:"url"`
}

