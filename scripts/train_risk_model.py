#!/usr/bin/env python3
"""
Train a simple risk/fraud classifier from the CSV produced by the training-data endpoint.
Usage:
  pip install -r scripts/requirements-ml.txt
  python scripts/train_risk_model.py [path/to/risk_training_data.csv]
If no path is given, looks for risk_training_data.csv in the current directory.
"""
import sys
from pathlib import Path

import pandas as pd
from sklearn.ensemble import RandomForestClassifier
from sklearn.model_selection import train_test_split


FEATURE_COLUMNS = [
    "totalCount",
    "failureCount",
    "failureRate",
    "countLast1Min",
    "avgAmount",
    "maxAmount",
]
TARGET_COLUMN = "label"


def main() -> None:
    if len(sys.argv) > 1:
        csv_path = Path(sys.argv[1])
    else:
        csv_path = Path("risk_training_data.csv")

    if not csv_path.exists():
        print(f"File not found: {csv_path}")
        print("Generate it first: start the app, then curl the training-data endpoint.")
        print("Example: curl -s 'http://localhost:8080/api/v1/risk/demo/training-data?rows=500' -o risk_training_data.csv")
        sys.exit(1)

    df = pd.read_csv(csv_path)
    for col in FEATURE_COLUMNS + [TARGET_COLUMN]:
        if col not in df.columns:
            print(f"Missing column: {col}. Expected columns: {list(df.columns)}")
            sys.exit(1)

    X = df[FEATURE_COLUMNS]
    y = df[TARGET_COLUMN]

    X_train, X_test, y_train, y_test = train_test_split(
        X, y, test_size=0.2, random_state=42, stratify=y
    )

    model = RandomForestClassifier(n_estimators=100, random_state=42)
    model.fit(X_train, y_train)

    score = model.score(X_test, y_test)
    print(f"Test accuracy: {score:.4f}")
    print("Feature importances:", dict(zip(FEATURE_COLUMNS, model.feature_importances_.round(4))))

    # Optional: save model for later use (e.g. load in Java or another service)
    out_path = Path(__file__).parent / "risk_model.joblib"
    try:
        import joblib
        joblib.dump(model, out_path)
        print(f"Model saved to {out_path}")
    except ImportError:
        pass  # joblib not required for basic run


if __name__ == "__main__":
    main()
