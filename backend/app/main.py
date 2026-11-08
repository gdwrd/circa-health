import os
import secrets
from contextlib import asynccontextmanager
from datetime import date

from fastapi import APIRouter, BackgroundTasks, Depends, FastAPI, HTTPException, Request, Response
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from . import db, notify, vm

bearer = HTTPBearer(auto_error=False)


def auth(cred: HTTPAuthorizationCredentials | None = Depends(bearer)) -> None:
    token = os.environ.get("API_TOKEN", "")
    if not token or cred is None or not secrets.compare_digest(cred.credentials, token):
        raise HTTPException(401, "unauthorized")


def rescore(dates: set[date]) -> None:
    pass  # placeholder until T4


@asynccontextmanager
async def lifespan(_: FastAPI):
    db.init()
    yield


app = FastAPI(lifespan=lifespan)
api = APIRouter(dependencies=[Depends(auth)])


@app.get("/health")
def health() -> dict:
    return {"status": "ok"}


@api.post("/api/v1/import/prometheus")
async def ingest(request: Request, tasks: BackgroundTasks) -> Response:
    body = await request.body()
    r = vm.forward_import(body)
    if r.is_success:
        tasks.add_task(rescore, vm.dates_in_body(body))
    return Response(r.content, r.status_code)


@api.get("/today")
def today() -> dict:
    return {"status": "learning"}


@api.get("/sleep")
def sleep() -> dict:
    return {"status": "learning"}


@api.get("/trends")
def trends() -> dict:
    return {"status": "learning"}


@api.get("/notifications/pending")
def pending() -> list[dict]:
    return notify.pending()


@api.post("/notifications/{id}/ack")
def ack(id: int) -> dict:
    notify.ack(id)
    return {"status": "ok"}


app.include_router(api)
