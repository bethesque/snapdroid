/*
 *     This file is part of snapcast
 *     Copyright (C) 2014-2018  Johannes Pohl
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.badaix.snapcast;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import java.time.format.DateTimeFormatter;
import java.util.Locale;

import de.badaix.snapcast.calendar.CalendarAlarmScheduler;
import de.badaix.snapcast.calendar.CalendarNotification;
import de.badaix.snapcast.control.json.Client;
import de.badaix.snapcast.control.json.Group;
import de.badaix.snapcast.control.json.ServerStatus;


/**
 * A simple {@link Fragment} subclass.
 * Activities that contain this fragment must implement the
 * {@link GroupItem.GroupItemListener} interface
 * to handle interaction events.
 */
public class GroupListFragment extends Fragment {

    private static final String TAG = "GroupList";
    private static final DateTimeFormatter NEXT_NOTIFICATION_FORMATTER = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.getDefault());

    private GroupItem.GroupItemListener groupItemListener;
    private GroupAdapter groupAdapter;
    private ServerStatus serverStatus = null;
    private TextView tvNextNotification;

    public GroupListFragment() {
        // Required empty public constructor
    }


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
//        if (getArguments() != null) {
//            mParam1 = getArguments().getString(ARG_PARAM1);
//        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        Log.d(TAG, "onCreateView: " + this);
        View view = inflater.inflate(R.layout.fragment_group_list, container, false);
        ListView lvGroup = view.findViewById(R.id.lvGroup);
        tvNextNotification = view.findViewById(R.id.tvNextNotification);
        groupAdapter = new GroupAdapter(getContext(), groupItemListener);
        groupAdapter.updateServer(serverStatus);
        lvGroup.setAdapter(groupAdapter);
        updateGui();
        updateNextNotification();
        return view;
    }


    private void updateGui() {
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        if (context instanceof GroupItem.GroupItemListener) {
            groupItemListener = (GroupItem.GroupItemListener) context;
        } else {
            throw new RuntimeException(context
                    + " must implement GroupItemListener");
        }
        updateGui();
    }

    @Override
    public void onDetach() {
        super.onDetach();
        groupItemListener = null;
    }

    public void updateServer(final ServerStatus serverStatus) {
        this.serverStatus = serverStatus;
        if (groupAdapter != null)
            groupAdapter.updateServer(serverStatus);
        updateNextNotification();
    }

    public void updateNextNotification() {
        FragmentActivity activity = getActivity();
        if ((activity == null) || (tvNextNotification == null))
            return;
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (tvNextNotification == null)
                    return;
                CalendarNotification next = CalendarAlarmScheduler.getNext(activity);
                if (next == null)
                    tvNextNotification.setText(R.string.no_upcoming_notification);
                else
                    tvNextNotification.setText(activity.getString(R.string.next_notification, next.getSummary(), next.getPlayDateTime().format(NEXT_NOTIFICATION_FORMATTER)));
            }
        });
    }

    public class GroupAdapter extends ArrayAdapter<Group> {
        private final Context context;
        private final GroupItem.GroupItemListener listener;
        private ServerStatus serverStatus = new ServerStatus();

        GroupAdapter(Context context, GroupItem.GroupItemListener listener) {
            super(context, 0);
            this.context = context;
            this.listener = listener;
        }

        @Override
        public
        @NonNull
        View getView(int position, @Nullable View convertView,
                     @NonNull ViewGroup parent) {
            Group group = getItem(position);
            final GroupItem groupItem;

            if (convertView != null) {
                groupItem = (GroupItem) convertView;
                groupItem.setGroup(group);
            } else {
                groupItem = new GroupItem(context, serverStatus, group);
            }
            groupItem.setListener(listener);
            return groupItem;
        }

        void updateServer(final ServerStatus serverStatus) {
            if (serverStatus != null) {
                GroupAdapter.this.serverStatus = serverStatus;
                update();
            }
        }


        void update() {
            FragmentActivity activity = getActivity();
            if (activity == null)
                return;
            final String ownClientId = SnapclientService.getUniqueId(context);
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    clear();
                    for (Group group : GroupAdapter.this.serverStatus.getGroups()) {
                        if (group.getClients().isEmpty())
                            continue;

                        Client ownClient = null;
                        for (Client client : group.getClients()) {
                            if ((client == null) || client.isDeleted() || !ownClientId.equals(client.getId()))
                                continue;
                            ownClient = client;
                            break;
                        }

                        if (ownClient != null)
                            add(group);
                    }

                    if (getActivity() != null)
                        notifyDataSetChanged();
                }
            });
        }
    }


}
